import { Hono } from 'hono';
import { Env } from '../../config/env';
import { createSupabaseDb } from '../../database/supabase';
import { AppVariables, authMiddleware } from '../../middleware/auth';
import { rateLimitMiddleware } from '../../middleware/rateLimit';
import { AuthService } from './auth.service';

export const authRoutes = new Hono<{ Bindings: Env; Variables: AppVariables }>();

authRoutes.post(
  '/check-phone',
  rateLimitMiddleware({ prefix: 'auth_check_phone', maxRequests: 15, windowMs: 60_000, blockMs: 300_000 }),
  async (c) => {
    const body = await c.req.json<{ phone?: string }>();
    const service = new AuthService(c.env, createSupabaseDb(c.env));
    const result = await service.checkPhoneAvailable({
      phone: body.phone ?? '',
      clientIp: c.req.header('CF-Connecting-IP'),
    });
    return c.json(result, 200);
  }
);

authRoutes.post(
  '/register',
  rateLimitMiddleware({ prefix: 'auth_register', maxRequests: 8, windowMs: 60_000, blockMs: 300_000 }),
  async (c) => {
    const body = await c.req.json<{
      phone?: string;
      countryIso2?: string;
      callingCode?: string;
      pin?: string;
      confirmPin?: string;
      displayName?: string;
      deviceName?: string;
      appVersion?: string;
    }>();

    const service = new AuthService(c.env, createSupabaseDb(c.env));
    const result = await service.register({
      phone: body.phone ?? '',
      countryIso2: body.countryIso2 ?? 'US',
      callingCode: body.callingCode ?? '+1',
      pin: body.pin ?? '',
      confirmPin: body.confirmPin ?? '',
      displayName: body.displayName,
      deviceName: body.deviceName,
      appVersion: body.appVersion,
      clientIp: c.req.header('CF-Connecting-IP'),
    });
    return c.json(result, 201);
  }
);

authRoutes.post(
  '/login',
  rateLimitMiddleware({ prefix: 'auth_login', maxRequests: 12, windowMs: 60_000, blockMs: 300_000 }),
  async (c) => {
    const body = await c.req.json<{
      phone?: string;
      pin?: string;
      deviceName?: string;
      appVersion?: string;
    }>();

    const service = new AuthService(c.env, createSupabaseDb(c.env));
    const result = await service.login({
      phone: body.phone ?? '',
      pin: body.pin ?? '',
      deviceName: body.deviceName,
      appVersion: body.appVersion,
      clientIp: c.req.header('CF-Connecting-IP'),
    });
    return c.json(result, 200);
  }
);

authRoutes.post(
  '/refresh',
  rateLimitMiddleware({ prefix: 'auth_refresh', maxRequests: 30, windowMs: 60_000 }),
  async (c) => {
    const body = await c.req.json<{ refreshToken?: string }>();
    const service = new AuthService(c.env, createSupabaseDb(c.env));
    const result = await service.refresh(body.refreshToken ?? '', c.req.header('CF-Connecting-IP'));
    return c.json(result, 200);
  }
);

authRoutes.post('/logout', authMiddleware, async (c) => {
  const auth = c.get('auth');
  const service = new AuthService(c.env, c.get('db'));
  await service.logout(auth.sessionId, auth.userId);
  return c.json({ ok: true });
});

authRoutes.get('/sessions', authMiddleware, async (c) => {
  const auth = c.get('auth');
  const service = new AuthService(c.env, c.get('db'));
  const sessions = await service.listActiveSessions(auth.userId, auth.sessionId);
  return c.json({ sessions });
});

authRoutes.delete('/sessions/:sessionId', authMiddleware, async (c) => {
  const auth = c.get('auth');
  const targetSessionId = c.req.param('sessionId') ?? '';
  if (!targetSessionId) {
    return c.json({ error: 'session_not_found', message: 'Session not found or already revoked.' }, 404);
  }
  const service = new AuthService(c.env, c.get('db'));
  const revoked = await service.revokeSession(auth.userId, targetSessionId);
  if (!revoked) {
    return c.json({ error: 'session_not_found', message: 'Session not found or already revoked.' }, 404);
  }
  return c.json({ ok: true, revokedSessionId: targetSessionId });
});

authRoutes.post('/logout-all', authMiddleware, async (c) => {
  const auth = c.get('auth');
  const body = await c.req.json<{ keepCurrent?: boolean }>().catch(() => ({ keepCurrent: false }));
  const service = new AuthService(c.env, c.get('db'));
  const result = await service.logoutAllDevices(
    auth.userId,
    auth.sessionId,
    auth.deviceId,
    Boolean(body.keepCurrent),
    c.req.header('CF-Connecting-IP')
  );
  return c.json(result, 200);
});

authRoutes.post(
  '/pin/change',
  authMiddleware,
  rateLimitMiddleware({ prefix: 'auth_pin_change', maxRequests: 10, windowMs: 300_000, blockMs: 300_000, keyScope: 'ip_and_user' }),
  async (c) => {
    const auth = c.get('auth');
    const body = await c.req.json<{
      currentPin?: string;
      newPin?: string;
      confirmNewPin?: string;
    }>();
    const service = new AuthService(c.env, c.get('db'));
    const result = await service.changeLoginPin(
      auth.userId,
      auth.sessionId,
      {
        currentPin: body.currentPin ?? '',
        newPin: body.newPin ?? '',
        confirmNewPin: body.confirmNewPin ?? '',
      },
      c.req.header('CF-Connecting-IP')
    );
    return c.json(result, 200);
  }
);

authRoutes.post(
  '/recovery-code/generate',
  authMiddleware,
  rateLimitMiddleware({ prefix: 'auth_recovery_gen', maxRequests: 8, windowMs: 300_000, blockMs: 300_000, keyScope: 'ip_and_user' }),
  async (c) => {
    const auth = c.get('auth');
    const body = await c.req.json<{ currentPin?: string }>();
    const service = new AuthService(c.env, c.get('db'));
    const result = await service.generateRecoveryCode(
      auth.userId,
      body.currentPin ?? '',
      c.req.header('CF-Connecting-IP')
    );
    return c.json(result, 200);
  }
);

authRoutes.post(
  '/pin/reset',
  rateLimitMiddleware({ prefix: 'auth_pin_reset', maxRequests: 10, windowMs: 300_000, blockMs: 600_000 }),
  async (c) => {
    const body = await c.req.json<{
      phone?: string;
      recoveryCode?: string;
      newPin?: string;
      confirmNewPin?: string;
      deviceName?: string;
      appVersion?: string;
    }>();
    const service = new AuthService(c.env, createSupabaseDb(c.env));
    const result = await service.resetLoginPinWithRecoveryCode({
      phone: body.phone ?? '',
      recoveryCode: body.recoveryCode ?? '',
      newPin: body.newPin ?? '',
      confirmNewPin: body.confirmNewPin ?? '',
      deviceName: body.deviceName,
      appVersion: body.appVersion,
      clientIp: c.req.header('CF-Connecting-IP'),
    });
    return c.json(result, 200);
  }
);
