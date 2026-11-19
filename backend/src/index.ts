import { Hono } from 'hono';
import { Env } from './config/env';
import { createSupabaseDb } from './database/supabase';
import { AppVariables } from './middleware/auth';
import { handleWorkerError } from './middleware/errorHandler';
import { authRoutes } from './modules/auth/auth.routes';
import { callsRoutes } from './modules/calls/calls.routes';
import { conversationsRoutes } from './modules/conversations/conversations.routes';
import { groupsRoutes } from './modules/groups/groups.routes';
import { mediaRoutes } from './modules/media/media.routes';
import { messagesRoutes } from './modules/messages/messages.routes';
import { notificationsRoutes } from './modules/notifications/notifications.routes';
import { ownerRoutes } from './modules/owner/owner.routes';
import { privacyRoutes } from './modules/privacy/privacy.routes';
import { profilesRoutes } from './modules/profiles/profiles.routes';
import { reportsRoutes } from './modules/reports/reports.routes';
import { usersRoutes } from './modules/users/users.routes';
import { verifyJwt } from './security/jwt';

export { UserRealtimeCoordinator } from './durable-objects/UserRealtimeCoordinator';
export { ConversationCoordinator } from './durable-objects/ConversationCoordinator';

const app = new Hono<{ Bindings: Env; Variables: AppVariables }>();

app.use('*', async (c, next) => {
  await next();
  c.header('X-Content-Type-Options', 'nosniff');
  c.header('Referrer-Policy', 'no-referrer');
  if (c.req.path.startsWith('/v1/auth/') || c.req.path.startsWith('/v1/owner')) {
    c.header('Cache-Control', 'no-store');
  }
});

app.onError((err, c) => handleWorkerError(err, c));

app.get('/health', (c) => {
  return c.json({
    service: 'my-chatting-pro-api',
    platform: 'cloudflare-workers-supabase',
    database: 'supabase-postgresql',
    storage: 'supabase-private-storage',
    environment: c.env.ENVIRONMENT,
    timestampMs: Date.now(),
  });
});

app.route('/v1/auth', authRoutes);
app.route('/v1/users', usersRoutes);
app.route('/v1/profiles', profilesRoutes);
app.route('/v1/conversations', conversationsRoutes);
app.route('/v1/messages', messagesRoutes);
app.route('/v1/groups', groupsRoutes);
app.route('/v1/media', mediaRoutes);
app.route('/v1/calls', callsRoutes);
app.route('/v1/privacy', privacyRoutes);
app.route('/v1/reports', reportsRoutes);
app.route('/v1/notifications', notificationsRoutes);
app.route('/v1/owner', ownerRoutes);

/**
 * Authenticated WebSocket entry point routed to per-user Durable Object.
 */
app.get('/v1/ws', async (c) => {
  if (c.req.header('Upgrade')?.toLowerCase() !== 'websocket') {
    return c.json({ error: 'upgrade_required', message: 'Expected WebSocket upgrade.' }, 426);
  }

  const authHeader = c.req.header('Authorization');
  const bearerToken =
    authHeader && authHeader.startsWith('Bearer ') ? authHeader.slice(7).trim() : null;
  if (!bearerToken) {
    return c.json({ error: 'unauthorized', message: 'Bearer token required for WebSocket.' }, 401);
  }

  const payload = await verifyJwt(bearerToken, c.env.JWT_ACCESS_SECRET, 'access');
  if (!payload) {
    return c.json({ error: 'unauthorized', message: 'Invalid or expired access token.' }, 401);
  }

  const db = createSupabaseDb(c.env);
  const session = await db.verifyActiveSession(payload.sid, payload.sub);
  if (!session || session.revoked_at_ms !== null || session.account_status !== 'active') {
    return c.json({ error: 'session_revoked', message: 'Session is revoked.' }, 401);
  }

  const stub = c.env.USER_COORDINATOR.get(c.env.USER_COORDINATOR.idFromName(payload.sub));
  const headers = new Headers(c.req.raw.headers);
  headers.set('X-Auth-User-Id', payload.sub);
  headers.set('X-Auth-Device-Id', payload.did);
  headers.set('X-Auth-Session-Id', payload.sid);

  const forwarded = new Request(c.req.url, {
    method: 'GET',
    headers,
  });

  return stub.fetch(forwarded);
});

export default app;
