import { Hono } from 'hono';
import { Env } from '../../config/env';
import { AppVariables, authMiddleware } from '../../middleware/auth';
import { ApiError } from '../../middleware/errorHandler';

export const notificationsRoutes = new Hono<{ Bindings: Env; Variables: AppVariables }>();

/**
 * Registers only genuine Non-Firebase notification transports:
 * - 'websocket_foreground': Active Cloudflare Durable Object WebSocket / Android Foreground Service
 * - 'unified_push': Optional user-configured UnifiedPush HTTP endpoint (e.g. ntfy)
 * Firebase Cloud Messaging (FCM) is strictly excluded.
 */
notificationsRoutes.post('/endpoints', authMiddleware, async (c) => {
  const auth = c.get('auth');
  const db = c.get('db');
  const body = await c.req.json<{
    transport?: 'unified_push' | 'websocket_foreground';
    endpointUrl?: string | null;
  }>();

  const transport = body.transport === 'unified_push' ? 'unified_push' : 'websocket_foreground';
  if (transport === 'unified_push') {
    if (!body.endpointUrl || !body.endpointUrl.startsWith('https://')) {
      throw new ApiError(400, 'invalid_endpoint', 'UnifiedPush endpoint must be a valid HTTPS URL.');
    }
  }

  await db.upsertNotificationEndpoint({
    id: crypto.randomUUID(),
    userId: auth.userId,
    deviceId: auth.deviceId,
    transport,
    endpointUrl: transport === 'unified_push' ? body.endpointUrl ?? null : null,
    updatedAtMs: Date.now(),
  });

  return c.json({ ok: true, transport });
});

notificationsRoutes.delete('/endpoints', authMiddleware, async (c) => {
  const auth = c.get('auth');
  const db = c.get('db');
  await db.deleteNotificationEndpoint(auth.userId, auth.deviceId);
  return c.json({ ok: true });
});
