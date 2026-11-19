import { Hono } from 'hono';
import { Env } from '../../config/env';
import { AppVariables, authMiddleware } from '../../middleware/auth';

/**
 * Reserved private group module stub.
 * MY CHATTING PRO restricts bottom navigation to Chats, Add Chat, and Settings
 * with no public social feeds, channels, or stories.
 */
export const groupsRoutes = new Hono<{ Bindings: Env; Variables: AppVariables }>();

groupsRoutes.get('/', authMiddleware, async (c) => {
  return c.json({
    groups: [],
    note: 'Direct 1-to-1 private messaging is active; public feeds and channels are intentionally excluded.',
  });
});
