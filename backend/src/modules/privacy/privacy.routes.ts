import { Hono } from 'hono';
import { Env } from '../../config/env';
import { AppVariables, authMiddleware } from '../../middleware/auth';
import { ApiError } from '../../middleware/errorHandler';

export const privacyRoutes = new Hono<{ Bindings: Env; Variables: AppVariables }>();

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
  return c.json({ ok: true, blockedUserId: targetUserId });
});

privacyRoutes.delete('/block/:targetUserId', authMiddleware, async (c) => {
  const auth = c.get('auth');
  const db = c.get('db');
  const targetUserId = c.req.param('targetUserId') ?? '';
  await db.unblockUser(auth.userId, targetUserId);
  return c.json({ ok: true, unblockedUserId: targetUserId });
});
