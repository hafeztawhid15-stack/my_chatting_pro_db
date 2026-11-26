import { Hono } from 'hono';
import { Env } from '../../config/env';
import { AppVariables, authMiddleware } from '../../middleware/auth';
import { rateLimitMiddleware } from '../../middleware/rateLimit';
import { createSupabaseStorage } from '../../storage/supabaseStorage';
import { MessagesService, MessageType } from './messages.service';

export const messagesRoutes = new Hono<{ Bindings: Env; Variables: AppVariables }>();

messagesRoutes.get('/:conversationId', authMiddleware, async (c) => {
  const auth = c.get('auth');
  const conversationId = c.req.param('conversationId') ?? '';
  const beforeTsRaw = c.req.query('beforeTs');
  const beforeId = c.req.query('beforeId');
  const limitRaw = c.req.query('limit');

  const beforeTs = beforeTsRaw ? Number.parseInt(beforeTsRaw, 10) : undefined;
  const limit = limitRaw ? Number.parseInt(limitRaw, 10) : undefined;

  const service = new MessagesService(c.env, c.get('db'), createSupabaseStorage(c.env));
  const result = await service.listMessages(conversationId, auth.userId, {
    beforeTs: Number.isFinite(beforeTs) ? beforeTs : undefined,
    beforeId: beforeId ?? undefined,
    limit: Number.isFinite(limit) ? limit : undefined,
  });
  return c.json(result);
});

messagesRoutes.post(
  '/',
  authMiddleware,
  rateLimitMiddleware({ prefix: 'send_msg', maxRequests: 60, windowMs: 60_000, keyScope: 'ip_and_user' }),
  async (c) => {
    const auth = c.get('auth');
    const body = await c.req.json<{
      conversationId?: string;
      clientMessageId?: string;
      messageType?: MessageType;
      bodyText?: string | null;
      mediaId?: string | null;
      replyToMessageId?: string | null;
    }>();

    const service = new MessagesService(c.env, c.get('db'), createSupabaseStorage(c.env));
    const msg = await service.sendMessage(auth.userId, {
      conversationId: body.conversationId ?? '',
      clientMessageId: body.clientMessageId ?? '',
      messageType: body.messageType ?? 'text',
      bodyText: body.bodyText,
      mediaId: body.mediaId,
      replyToMessageId: body.replyToMessageId,
    });
    return c.json({ message: msg }, 201);
  }
);

messagesRoutes.post('/receipts', authMiddleware, async (c) => {
  const auth = c.get('auth');
  const body = await c.req.json<{
    conversationId?: string;
    messageIds?: string[];
    type?: 'delivered' | 'read';
  }>();

  const service = new MessagesService(c.env, c.get('db'), createSupabaseStorage(c.env));
  await service.markReceipts(auth.userId, {
    conversationId: body.conversationId ?? '',
    messageIds: Array.isArray(body.messageIds) ? body.messageIds : [],
    type: body.type === 'read' ? 'read' : 'delivered',
  });
  return c.json({ ok: true });
});

messagesRoutes.post('/:messageId/delete-for-me', authMiddleware, async (c) => {
  const auth = c.get('auth');
  const messageId = c.req.param('messageId') ?? '';
  const service = new MessagesService(c.env, c.get('db'), createSupabaseStorage(c.env));
  await service.deleteMessageForMe(auth.userId, messageId);
  return c.json({ ok: true, scope: 'me' });
});

messagesRoutes.delete('/:messageId', authMiddleware, async (c) => {
  const auth = c.get('auth');
  const messageId = c.req.param('messageId') ?? '';
  const scope = c.req.query('scope');
  const service = new MessagesService(c.env, c.get('db'), createSupabaseStorage(c.env));
  if (scope === 'me') {
    await service.deleteMessageForMe(auth.userId, messageId);
    return c.json({ ok: true, scope: 'me' });
  }
  await service.deleteOwnMessage(auth.userId, messageId);
  return c.json({ ok: true, scope: 'everyone' });
});
