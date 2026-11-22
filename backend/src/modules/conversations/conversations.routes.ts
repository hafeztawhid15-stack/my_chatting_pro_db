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

conversationsRoutes.patch('/:conversationId/preferences', authMiddleware, async (c) => {
  const auth = c.get('auth');
  const conversationId = c.req.param('conversationId') ?? '';
  const body = await c.req.json<{
    isPinned?: boolean;
    isArchived?: boolean;
    mutedUntilMs?: number | null;
    customSoundUri?: string | null;
    customSoundLabel?: string | null;
    markedUnread?: boolean;
  }>();
  const service = new ConversationsService(c.get('db'));
  const result = await service.updateConversationPreferences(auth.userId, conversationId, body, false);
  return c.json(result, 200);
});

conversationsRoutes.post('/:conversationId/clear', authMiddleware, async (c) => {
  const auth = c.get('auth');
  const conversationId = c.req.param('conversationId') ?? '';
  const service = new ConversationsService(c.get('db'));
  const result = await service.clearConversationForUser(auth.userId, conversationId, false);
  return c.json(result, 200);
});

conversationsRoutes.delete('/:conversationId', authMiddleware, async (c) => {
  const auth = c.get('auth');
  const conversationId = c.req.param('conversationId') ?? '';
  const service = new ConversationsService(c.get('db'));
  const result = await service.deleteConversationForUser(auth.userId, conversationId, false);
  return c.json(result, 200);
});

conversationsRoutes.post('/:conversationId/move-to-private', authMiddleware, async (c) => {
  const auth = c.get('auth');
  const conversationId = c.req.param('conversationId') ?? '';
  const service = new ConversationsService(c.get('db'));
  const result = await service.moveConversationToPrivate(auth.userId, conversationId);
  return c.json(result, 200);
});

conversationsRoutes.get('/:conversationId/peer-profile', authMiddleware, async (c) => {
  const auth = c.get('auth');
  const conversationId = c.req.param('conversationId') ?? '';
  const service = new ConversationsService(c.get('db'));
  const result = await service.getPeerProfile(auth.userId, conversationId, false);
  return c.json(result, 200);
});
