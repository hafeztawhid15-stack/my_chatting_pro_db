import { Hono } from 'hono';
import { Env } from '../../config/env';
import { AppVariables, authMiddleware } from '../../middleware/auth';
import { ApiError } from '../../middleware/errorHandler';
import { CallsService } from '../calls/calls.service';

export const privacyRoutes = new Hono<{ Bindings: Env; Variables: AppVariables }>();

async function notifyBlockChanged(
  env: Env,
  actorUserId: string,
  targetUserId: string,
  isBlockedByActor: boolean,
  blockedBetween: boolean
): Promise<void> {
  const payload = JSON.stringify({
    actorUserId,
    targetUserId,
    isBlockedByActor,
    blockedBetween,
  });
  await Promise.allSettled(
    [actorUserId, targetUserId].map((uid) => {
      const stub = env.USER_COORDINATOR.get(env.USER_COORDINATOR.idFromName(uid));
      return stub.fetch('https://do.internal/internal/block-changed', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: payload,
      });
    })
  );
}

privacyRoutes.get('/blocked', authMiddleware, async (c) => {
  const auth = c.get('auth');
  const db = c.get('db');
  const rows = await db.listBlockedUsers(auth.userId);

  return c.json({
    blockedUsers: rows.map((r) => ({
      userId: r.user_id,
      displayName: r.display_name,
      avatarMediaId: r.avatar_media_id,
      blockedAtMs: r.blocked_at_ms,
    })),
  });
});

privacyRoutes.post('/block', authMiddleware, async (c) => {
  const auth = c.get('auth');
  const db = c.get('db');
  const body = await c.req.json<{ targetUserId?: string }>();
  const targetUserId = body.targetUserId ?? '';
  if (!targetUserId || targetUserId === auth.userId) {
    throw new ApiError(400, 'invalid_target', 'Valid target user ID is required.');
  }

  await db.blockUser(auth.userId, targetUserId, Date.now());
  await new CallsService(c.env, db)
    .terminateActiveCallsBetween(auth.userId, targetUserId, auth.userId)
    .catch(() => undefined);
  await notifyBlockChanged(c.env, auth.userId, targetUserId, true, true);

  return c.json({ ok: true, blockedUserId: targetUserId });
});

privacyRoutes.delete('/block/:targetUserId', authMiddleware, async (c) => {
  const auth = c.get('auth');
  const db = c.get('db');
  const targetUserId = c.req.param('targetUserId') ?? '';
  if (!targetUserId) {
    throw new ApiError(400, 'invalid_target', 'Valid target user ID is required.');
  }
  await db.unblockUser(auth.userId, targetUserId);
  const blockedBetween = await db.isBlockedBetween(auth.userId, targetUserId);
  await notifyBlockChanged(c.env, auth.userId, targetUserId, false, blockedBetween);

  return c.json({ ok: true, unblockedUserId: targetUserId, blockedBetween });
});
