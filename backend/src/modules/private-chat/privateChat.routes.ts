import { Hono } from 'hono';
import { Env } from '../../config/env';
import { AppVariables, authMiddleware, privateChatAuthMiddleware } from '../../middleware/auth';
import { rateLimitMiddleware } from '../../middleware/rateLimit';
import { createSupabaseStorage } from '../../storage/supabaseStorage';
import { ConversationsService } from '../conversations/conversations.service';
import { MessagesService, MessageType } from '../messages/messages.service';
import { UsersService } from '../users/users.service';
import { PrivateChatService } from './privateChat.service';

export const privateChatRoutes = new Hono<{ Bindings: Env; Variables: AppVariables }>();

// All Private Chat & Private PIN routes require active Account Authentication
privateChatRoutes.use('*', authMiddleware);

privateChatRoutes.get('/pin/status', async (c) => {
  const auth = c.get('auth');
  const service = new PrivateChatService(c.env, c.get('db'));
  const status = await service.getPinStatus(auth.userId);
  return c.json(status);
});

privateChatRoutes.post(
  '/pin/setup',
  rateLimitMiddleware({ prefix: 'private_pin_setup', maxRequests: 10, windowMs: 300_000, blockMs: 300_000, keyScope: 'ip_and_user' }),
  async (c) => {
    const auth = c.get('auth');
    const body = await c.req.json<{ pin?: string; confirmPin?: string }>();
    const service = new PrivateChatService(c.env, c.get('db'));
    const result = await service.setupPrivatePin({
      userId: auth.userId,
      sessionId: auth.sessionId,
      deviceId: auth.deviceId,
      pin: body.pin ?? '',
      confirmPin: body.confirmPin ?? '',
      clientIp: c.req.header('CF-Connecting-IP'),
    });
    return c.json(result, 201);
  }
);

privateChatRoutes.post(
  '/pin/unlock',
  rateLimitMiddleware({ prefix: 'private_pin_unlock', maxRequests: 15, windowMs: 300_000, blockMs: 300_000, keyScope: 'ip_and_user' }),
  async (c) => {
    const auth = c.get('auth');
    const body = await c.req.json<{ pin?: string }>();
    const service = new PrivateChatService(c.env, c.get('db'));
    const result = await service.unlockPrivateChat({
      userId: auth.userId,
      sessionId: auth.sessionId,
      deviceId: auth.deviceId,
      pin: body.pin ?? '',
      clientIp: c.req.header('CF-Connecting-IP'),
    });
    return c.json(result, 200);
  }
);

privateChatRoutes.post(
  '/pin/change',
  rateLimitMiddleware({ prefix: 'private_pin_change', maxRequests: 10, windowMs: 300_000, blockMs: 300_000, keyScope: 'ip_and_user' }),
  async (c) => {
    const auth = c.get('auth');
    const body = await c.req.json<{ oldPin?: string; newPin?: string; confirmNewPin?: string }>();
    const service = new PrivateChatService(c.env, c.get('db'));
    const result = await service.changePrivatePin({
      userId: auth.userId,
      sessionId: auth.sessionId,
      deviceId: auth.deviceId,
      oldPin: body.oldPin ?? '',
      newPin: body.newPin ?? '',
      confirmNewPin: body.confirmNewPin ?? '',
      clientIp: c.req.header('CF-Connecting-IP'),
    });
    return c.json(result, 200);
  }
);

privateChatRoutes.post(
  '/pin/reset',
  rateLimitMiddleware({ prefix: 'private_pin_reset', maxRequests: 8, windowMs: 600_000, blockMs: 600_000, keyScope: 'ip_and_user' }),
  async (c) => {
    const auth = c.get('auth');
    const body = await c.req.json<{
      loginPin?: string;
      newPrivatePin?: string;
      confirmNewPrivatePin?: string;
    }>();
    const service = new PrivateChatService(c.env, c.get('db'));
    const result = await service.resetPrivatePinWithAccountAuth({
      userId: auth.userId,
      sessionId: auth.sessionId,
      deviceId: auth.deviceId,
      loginPin: body.loginPin ?? '',
      newPrivatePin: body.newPrivatePin ?? '',
      confirmNewPrivatePin: body.confirmNewPrivatePin ?? '',
      clientIp: c.req.header('CF-Connecting-IP'),
    });
    return c.json(result, 200);
  }
);

// All Private Chat conversations & messages routes strictly enforce valid Private Unlock Token
privateChatRoutes.get('/conversations', privateChatAuthMiddleware, async (c) => {
  const auth = c.get('auth');
  const service = new ConversationsService(c.get('db'));
  const conversations = await service.listForUser(auth.userId, true);
  return c.json({ conversations });
});

privateChatRoutes.post('/conversations/direct', privateChatAuthMiddleware, async (c) => {
  const auth = c.get('auth');
  const body = await c.req.json<{ targetUserId?: string }>();
  const service = new ConversationsService(c.get('db'));
  const conversation = await service.getOrCreateDirectConversation(
    auth.userId,
    body.targetUserId ?? '',
    true
  );
  return c.json({ conversation }, 200);
});

privateChatRoutes.patch('/conversations/:conversationId/preferences', privateChatAuthMiddleware, async (c) => {
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
  const result = await service.updateConversationPreferences(auth.userId, conversationId, body, true);
  return c.json(result, 200);
});

privateChatRoutes.post('/conversations/:conversationId/clear', privateChatAuthMiddleware, async (c) => {
  const auth = c.get('auth');
  const conversationId = c.req.param('conversationId') ?? '';
  const service = new ConversationsService(c.get('db'));
  const result = await service.clearConversationForUser(auth.userId, conversationId, true);
  return c.json(result, 200);
});

privateChatRoutes.delete('/conversations/:conversationId', privateChatAuthMiddleware, async (c) => {
  const auth = c.get('auth');
  const conversationId = c.req.param('conversationId') ?? '';
  const service = new ConversationsService(c.get('db'));
  const result = await service.deleteConversationForUser(auth.userId, conversationId, true);
  return c.json(result, 200);
});

privateChatRoutes.post('/conversations/:conversationId/move-to-regular', privateChatAuthMiddleware, async (c) => {
  const auth = c.get('auth');
  const conversationId = c.req.param('conversationId') ?? '';
  const service = new ConversationsService(c.get('db'));
  const result = await service.moveConversationToRegular(auth.userId, conversationId);
  return c.json(result, 200);
});

privateChatRoutes.get('/conversations/:conversationId/peer-profile', privateChatAuthMiddleware, async (c) => {
  const auth = c.get('auth');
  const conversationId = c.req.param('conversationId') ?? '';
  const service = new ConversationsService(c.get('db'));
  const result = await service.getPeerProfile(auth.userId, conversationId, true);
  return c.json(result, 200);
});

privateChatRoutes.get('/users/search', privateChatAuthMiddleware, async (c) => {
  const auth = c.get('auth');
  const phone = c.req.query('phone') ?? '';
  const name = c.req.query('name');
  const service = new UsersService(c.get('db'));
  const user = await service.searchByPhoneAndOptionalName(auth.userId, phone, name);
  if (!user) {
    return c.json({ found: false, user: null, message: 'No My Chatting Pro account found.' }, 200);
  }
  return c.json({ found: true, user }, 200);
});

privateChatRoutes.get('/messages/:conversationId', privateChatAuthMiddleware, async (c) => {
  const auth = c.get('auth');
  const conversationId = c.req.param('conversationId') ?? '';
  const beforeTsRaw = c.req.query('beforeTs');
  const beforeId = c.req.query('beforeId');
  const limitRaw = c.req.query('limit');

  const beforeTs = beforeTsRaw ? Number.parseInt(beforeTsRaw, 10) : undefined;
  const limit = limitRaw ? Number.parseInt(limitRaw, 10) : undefined;

  const service = new MessagesService(c.env, c.get('db'), createSupabaseStorage(c.env));
  const result = await service.listMessages(
    conversationId,
    auth.userId,
    {
      beforeTs: Number.isFinite(beforeTs) ? beforeTs : undefined,
      beforeId: beforeId ?? undefined,
      limit: Number.isFinite(limit) ? limit : undefined,
    },
    true
  );
  return c.json(result);
});

privateChatRoutes.post(
  '/messages',
  privateChatAuthMiddleware,
  rateLimitMiddleware({ prefix: 'private_send_msg', maxRequests: 60, windowMs: 60_000, keyScope: 'ip_and_user' }),
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
    const msg = await service.sendMessage(
      auth.userId,
      {
        conversationId: body.conversationId ?? '',
        clientMessageId: body.clientMessageId ?? '',
        messageType: body.messageType ?? 'text',
        bodyText: body.bodyText,
        mediaId: body.mediaId,
        replyToMessageId: body.replyToMessageId,
      },
      true
    );
    return c.json({ message: msg }, 201);
  }
);

privateChatRoutes.post('/messages/receipts', privateChatAuthMiddleware, async (c) => {
  const auth = c.get('auth');
  const body = await c.req.json<{
    conversationId?: string;
    messageIds?: string[];
    type?: 'delivered' | 'read';
  }>();

  const service = new MessagesService(c.env, c.get('db'), createSupabaseStorage(c.env));
  await service.markReceipts(
    auth.userId,
    {
      conversationId: body.conversationId ?? '',
      messageIds: Array.isArray(body.messageIds) ? body.messageIds : [],
      type: body.type === 'read' ? 'read' : 'delivered',
    },
    true
  );
  return c.json({ ok: true });
});

privateChatRoutes.delete('/messages/:messageId', privateChatAuthMiddleware, async (c) => {
  const auth = c.get('auth');
  const messageId = c.req.param('messageId') ?? '';
  const service = new MessagesService(c.env, c.get('db'), createSupabaseStorage(c.env));
  await service.deleteOwnMessage(auth.userId, messageId, true);
  return c.json({ ok: true });
});
