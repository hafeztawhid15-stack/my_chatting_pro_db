import { Hono } from 'hono';
import { Env } from '../../config/env';
import { AppVariables, authMiddleware } from '../../middleware/auth';
import { ConversationsService } from './conversations.service';

export const conversationsRoutes = new Hono<{ Bindings: Env; Variables: AppVariables }>();

conversationsRoutes.get('/', authMiddleware, async (c) => {
  const auth = c.get('auth');
  const service = new ConversationsService(c.get('db'));
  const items = await service.listForUser(auth.userId);
  return c.json({ conversations: items });
});

conversationsRoutes.post('/direct', authMiddleware, async (c) => {
  const auth = c.get('auth');
  const body = await c.req.json<{ targetUserId?: string }>();
  const service = new ConversationsService(c.get('db'));
  const conversation = await service.getOrCreateDirectConversation(auth.userId, body.targetUserId ?? '');
  return c.json({ conversation }, 200);
});
