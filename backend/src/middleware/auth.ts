import { Context, Next } from 'hono';
import { AuthContext, Env } from '../config/env';
import { createSupabaseDb, DatabaseClient } from '../database/supabase';
import { verifyJwt } from '../security/jwt';

export type AppVariables = {
  auth: AuthContext;
  db: DatabaseClient;
};

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
