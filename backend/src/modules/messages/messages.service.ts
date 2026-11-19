import { Env } from '../../config/env';
import { DEFAULT_PAGE_LIMIT, MAX_PAGE_LIMIT } from '../../config/constants';
import { DatabaseClient, MessageQueryRow } from '../../database/supabase';
import { ApiError } from '../../middleware/errorHandler';
import { resolveStorageBucket, StorageClient } from '../../storage/supabaseStorage';
import { RealtimeEnvelope } from '../../websocket/events';
import { ConversationsService } from '../conversations/conversations.service';

export type MessageType = 'text' | 'image' | 'video' | 'voice' | 'audio' | 'file' | 'call_log';

export interface MessageDto {
  id: string;
  conversationId: string;
  senderId: string;
  clientMessageId: string;
  messageType: MessageType;
  bodyText: string | null;
  media: {
    id: string;
    category: string;
    originalFilename: string;
    mimeType: string;
    sizeBytes: number;
    durationMs: number | null;
    width: number | null;
    height: number | null;
  } | null;
  replyToMessageId: string | null;
  deletedForAllAtMs: number | null;
  createdAtMs: number;
  deliveredAtMs: number | null;
  readAtMs: number | null;
}

export class MessagesService {
  constructor(
    private readonly env: Env,
    private readonly db: DatabaseClient,
    private readonly storage?: StorageClient
  ) {}

  async listMessages(
    conversationId: string,
    userId: string,
    cursor?: { beforeTs?: number; beforeId?: string; limit?: number }
  ): Promise<{ messages: MessageDto[]; hasMore: boolean; nextCursorTs: number | null; nextCursorId: string | null }> {
    const convService = new ConversationsService(this.db);
    await convService.assertMember(conversationId, userId);

    const limit = Math.min(Math.max(cursor?.limit ?? DEFAULT_PAGE_LIMIT, 1), MAX_PAGE_LIMIT);
    const rows = await this.db.listConversationMessages(
      conversationId,
      cursor?.beforeTs ?? null,
      cursor?.beforeId ?? null,
      limit + 1
    );

    const hasMore = rows.length > limit;
    const pageRows = hasMore ? rows.slice(0, limit) : rows;
    const oldest = pageRows[pageRows.length - 1];
    const messages = pageRows.reverse().map((r) => this.mapRowToDto(r));

    return {
      messages,
      hasMore,
      nextCursorTs: hasMore && oldest ? oldest.created_at_ms : null,
      nextCursorId: hasMore && oldest ? oldest.id : null,
    };
  }

  async sendMessage(
    senderId: string,
    input: {
      conversationId: string;
      clientMessageId: string;
      messageType: MessageType;
      bodyText?: string | null;
      mediaId?: string | null;
      replyToMessageId?: string | null;
    }
  ): Promise<MessageDto> {
    const convService = new ConversationsService(this.db);
    const memberIds = await convService.assertMember(input.conversationId, senderId);
    const recipientIds = memberIds.filter((id) => id !== senderId);

    for (const rid of recipientIds) {
      const blocked = await this.db.isBlockedBetween(senderId, rid);
      if (blocked) {
        throw new ApiError(403, 'user_blocked', 'Messaging is unavailable because a block exists between users.');
      }
    }

    if (!input.clientMessageId || input.clientMessageId.length > 64) {
      throw new ApiError(400, 'invalid_client_message_id', 'Valid clientMessageId is required for idempotency.');
    }

    const existing = await this.db.findMessageByClientId(senderId, input.clientMessageId);
    if (existing) {
      const found = await this.getSingleMessage(existing.id);
      if (found) return found;
    }

    const bodyText = input.bodyText?.trim() ? input.bodyText.slice(0, 8000) : null;
    if (input.messageType === 'text' && !bodyText) {
      throw new ApiError(400, 'empty_message', 'Text message cannot be empty.');
    }

    if (input.mediaId) {
      const media = await this.db.findCompletedConversationMedia(input.mediaId);
      if (!media || media.owner_user_id !== senderId || media.conversation_id !== input.conversationId) {
        throw new ApiError(403, 'invalid_media_attachment', 'Media object does not belong to sender or conversation.');
      }
    } else if (input.messageType !== 'text' && input.messageType !== 'call_log') {
      throw new ApiError(400, 'missing_media_id', 'Media message requires an uploaded mediaId.');
    }

    const messageId = crypto.randomUUID();
    const serverTimestampMs = Date.now();
    const preview = this.buildPreview(input.messageType, bodyText);

    await this.db.sendChatMessageTx({
      messageId,
      conversationId: input.conversationId,
      senderId,
      clientMessageId: input.clientMessageId,
      messageType: input.messageType,
      bodyText,
      mediaId: input.mediaId ?? null,
      replyToMessageId: input.replyToMessageId ?? null,
      preview,
      recipientIds,
      nowMs: serverTimestampMs,
    });

    const dto = await this.getSingleMessage(messageId);
    if (!dto) {
      throw new ApiError(500, 'message_persist_failed', 'Message failed to persist.');
    }

    const envelope: RealtimeEnvelope = {
      eventId: `msg:${messageId}`,
      type: 'message.new',
      timestampMs: serverTimestampMs,
      payload: { message: dto },
    };
    const convStub = this.env.CONVERSATION_COORDINATOR.get(
      this.env.CONVERSATION_COORDINATOR.idFromName(input.conversationId)
    );
    await convStub.fetch('https://do.internal/internal/fanout', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ memberIds, envelope }),
    });

    return dto;
  }

  async markReceipts(
    userId: string,
    input: { conversationId: string; messageIds: string[]; type: 'delivered' | 'read' }
  ): Promise<void> {
    if (input.messageIds.length === 0) return;
    const convService = new ConversationsService(this.db);
    const memberIds = await convService.assertMember(input.conversationId, userId);

    const now = Date.now();
    const boundedIds = input.messageIds.slice(0, 100);
    await this.db.markMessageReceiptsTx(input.conversationId, userId, boundedIds, input.type, now);

    const envelope: RealtimeEnvelope = {
      eventId: crypto.randomUUID(),
      type: input.type === 'delivered' ? 'message.delivered' : 'message.read',
      timestampMs: now,
      payload: {
        conversationId: input.conversationId,
        recipientId: userId,
        messageIds: boundedIds,
        atMs: now,
      },
    };
    const convStub = this.env.CONVERSATION_COORDINATOR.get(
      this.env.CONVERSATION_COORDINATOR.idFromName(input.conversationId)
    );
    await convStub.fetch('https://do.internal/internal/fanout', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ memberIds, envelope }),
    });
  }

  async deleteOwnMessage(userId: string, messageId: string): Promise<void> {
    const msg = await this.db.findMessageForDelete(messageId);
    if (!msg) {
      throw new ApiError(404, 'message_not_found', 'Message not found.');
    }
    if (msg.sender_id !== userId) {
      throw new ApiError(403, 'forbidden_delete', 'You can only delete your own messages.');
    }

    const convService = new ConversationsService(this.db);
    const memberIds = await convService.assertMember(msg.conversation_id, userId);

    const now = Date.now();
    const media = msg.media_id ? await this.db.findMediaObjectById(msg.media_id) : null;
    await this.db.deleteOwnMessageTx(messageId, msg.conversation_id, msg.media_id, now);

    if (media && this.storage) {
      const bucket = media.storage_bucket || resolveStorageBucket(this.env);
      await this.storage.deleteObjects(bucket, [media.storage_path]);
    }

    const envelope: RealtimeEnvelope = {
      eventId: `del:${messageId}`,
      type: 'message.deleted',
      timestampMs: now,
      payload: {
        conversationId: msg.conversation_id,
        messageId,
        deletedAtMs: now,
      },
    };
    const convStub = this.env.CONVERSATION_COORDINATOR.get(
      this.env.CONVERSATION_COORDINATOR.idFromName(msg.conversation_id)
    );
    await convStub.fetch('https://do.internal/internal/fanout', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ memberIds, envelope }),
    });
  }

  private async getSingleMessage(messageId: string): Promise<MessageDto | null> {
    const row = await this.db.getSingleMessageDetail(messageId);
    return row ? this.mapRowToDto(row) : null;
  }

  private mapRowToDto(r: MessageQueryRow): MessageDto {
    return {
      id: r.id,
      conversationId: r.conversation_id,
      senderId: r.sender_id,
      clientMessageId: r.client_message_id,
      messageType: r.message_type,
      bodyText: r.deleted_for_all_at_ms ? null : r.body_text,
      media:
        r.deleted_for_all_at_ms || !r.media_id || !r.original_filename || !r.mime_type
          ? null
          : {
              id: r.media_id,
              category: r.media_category ?? 'file',
              originalFilename: r.original_filename,
              mimeType: r.mime_type,
              sizeBytes: r.size_bytes ?? 0,
              durationMs: r.duration_ms,
              width: r.width,
              height: r.height,
            },
      replyToMessageId: r.reply_to_message_id,
      deletedForAllAtMs: r.deleted_for_all_at_ms,
      createdAtMs: r.created_at_ms,
      deliveredAtMs: r.delivered_at_ms,
      readAtMs: r.read_at_ms,
    };
  }

  private buildPreview(type: MessageType, bodyText: string | null): string {
    switch (type) {
      case 'image':
        return 'Photo';
      case 'video':
        return 'Video';
      case 'voice':
        return 'Voice message';
      case 'audio':
        return 'Audio file';
      case 'file':
        return 'Document / File';
      case 'call_log':
        return bodyText ?? 'Call';
      case 'text':
      default:
        return (bodyText ?? '').slice(0, 80);
    }
  }
}
