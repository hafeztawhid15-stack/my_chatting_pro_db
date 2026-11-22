import { Context, Next } from 'hono';
import { AuthContext, Env } from '../config/env';
import { createSupabaseDb, DatabaseClient } from '../database/supabase';
import { sha256Hex, verifyJwt } from '../security/jwt';

export type AppVariables = {
  auth: AuthContext;
  db: DatabaseClient;
  privateUnlock?: {
    userId: string;
    sessionId: string;
    pver: number;
  };
};

export async function derivePrivateUnlockSecret(env: Pick<Env, 'JWT_ACCESS_SECRET' | 'PIN_PEPPER'>): Promise<string> {
  return sha256Hex(`${env.JWT_ACCESS_SECRET}:private_unlock:${env.PIN_PEPPER}`);
}

export async function verifyPrivateUnlockToken(
  token: string | undefined | null,
  env: Pick<Env, 'JWT_ACCESS_SECRET' | 'PIN_PEPPER'>,
  db: DatabaseClient,
  auth: { userId: string; sessionId?: string }
): Promise<{ userId: string; sessionId: string; pver: number } | null> {
  if (!token || !token.trim()) return null;
  const secret = await derivePrivateUnlockSecret(env);
  const payload = await verifyJwt(token.trim(), secret, 'private_unlock');
  if (!payload || payload.sub !== auth.userId) return null;
  if (auth.sessionId && payload.sid !== auth.sessionId) return null;

  const pinState = await db.getUserPrivatePinState(auth.userId);
  if (!pinState || !pinState.private_pin_hash) return null;

  const currentVersion = pinState.private_pin_updated_at_ms ?? 0;
  if ((payload.pver ?? 0) !== currentVersion) {
    return null;
  }

  return {
    userId: auth.userId,
    sessionId: payload.sid,
    pver: currentVersion,
  };
}

export async function authMiddleware(
  c: Context<{ Bindings: Env; Variables: AppVariables }>,
  next: Next
) {
  const authHeader = c.req.header('Authorization');
  if (!authHeader || !authHeader.startsWith('Bearer ')) {
    return c.json({ error: 'unauthorized', message: 'Missing bearer token' }, 401);
  }
  const token = authHeader.slice(7).trim();
  const payload = await verifyJwt(token, c.env.JWT_ACCESS_SECRET, 'access');
  if (!payload) {
    return c.json({ error: 'unauthorized', message: 'Invalid or expired access token' }, 401);
  }

  const db = createSupabaseDb(c.env);
  const session = await db.verifyActiveSession(payload.sid, payload.sub);

  if (!session || session.revoked_at_ms !== null || session.account_status !== 'active') {
    return c.json({ error: 'session_revoked', message: 'Session is no longer active' }, 401);
  }

  c.set('auth', {
    userId: payload.sub,
    deviceId: payload.did,
    sessionId: payload.sid,
    userRole: session.user_role === 'owner' ? 'owner' : 'user',
  });
  c.set('db', db);
  await next();
}

export async function privateChatAuthMiddleware(
  c: Context<{ Bindings: Env; Variables: AppVariables }>,
  next: Next
) {
  const auth = c.get('auth');
  const db = c.get('db');
  const unlockToken = c.req.header('X-Private-Unlock-Token');
  const verified = await verifyPrivateUnlockToken(unlockToken, c.env, db, {
    userId: auth.userId,
    sessionId: auth.sessionId,
  });

  if (!verified) {
    return c.json(
      {
        error: 'private_chat_locked',
        message: 'Private Chat PIN verification is required.',
      },
      403
    );
  }

  c.set('privateUnlock', verified);
  await next();
}
