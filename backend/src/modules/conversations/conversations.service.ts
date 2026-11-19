import { DatabaseClient } from '../../database/supabase';
import { ApiError } from '../../middleware/errorHandler';

export interface ConversationSummary {
  id: string;
  type: 'direct' | 'group';
  peerUserId: string;
  peerDisplayName: string;
  peerAvatarMediaId: string | null;
  peerAbout: string;
  peerIsOnline: boolean;
  peerLastSeenMs: number | null;
  isBlockedByMe: boolean;
  hasBlockedMe: boolean;
  lastMessageId: string | null;
  lastMessagePreview: string | null;
  lastMessageType: string | null;
  lastMessageSenderId: string | null;
  lastMessageAtMs: number | null;
  unreadCount: number;
  updatedAtMs: number;
}

export class ConversationsService {
  constructor(private readonly db: DatabaseClient) {}

  async listForUser(userId: string): Promise<ConversationSummary[]> {
    const rows = await this.db.listUserConversations(userId);
    return rows.map((r) => ({
      id: r.id,
      type: r.type,
      peerUserId: r.peer_user_id,
      peerDisplayName: r.peer_display_name,
      peerAvatarMediaId: r.peer_avatar_media_id,
      peerAbout: r.peer_about_text,
      peerIsOnline: Boolean(r.peer_is_online) && !r.blocked_me,
      peerLastSeenMs: r.blocked_me ? null : r.peer_last_seen_ms,
      isBlockedByMe: Boolean(r.blocked_by_me),
      hasBlockedMe: Boolean(r.blocked_me),
      lastMessageId: r.last_message_id,
      lastMessagePreview: r.last_message_preview,
      lastMessageType: r.last_message_type,
      lastMessageSenderId: r.last_message_sender_id,
      lastMessageAtMs: r.last_message_at_ms,
      unreadCount: r.unread_count,
      updatedAtMs: r.updated_at_ms,
    }));
  }

  async getOrCreateDirectConversation(currentUserId: string, targetUserId: string): Promise<ConversationSummary> {
    if (currentUserId === targetUserId) {
      throw new ApiError(400, 'invalid_target', 'Cannot start a chat with yourself.');
    }

    const exists = await this.db.isActiveUserExists(targetUserId);
    if (!exists) {
      throw new ApiError(404, 'account_not_found', 'Target user does not exist.');
    }

    const pair = [currentUserId, targetUserId].sort();
    const directKey = `${pair[0]}:${pair[1]}`;
    const convId = await this.db.getOrCreateDirectConversation(
      currentUserId,
      targetUserId,
      crypto.randomUUID(),
      directKey,
      Date.now()
    );

    const all = await this.listForUser(currentUserId);
    const match = all.find((c) => c.id === convId);
    if (!match) {
      throw new ApiError(500, 'conversation_create_failed', 'Failed to load conversation.');
    }
    return match;
  }

  async assertMember(conversationId: string, userId: string): Promise<string[]> {
    const members = await this.db.listConversationMemberIds(conversationId);
    if (!members.includes(userId)) {
      throw new ApiError(403, 'forbidden_conversation', 'You are not a member of this conversation.');
    }
    return members;
  }
}
