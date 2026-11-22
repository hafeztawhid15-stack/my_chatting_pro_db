import { DatabaseClient } from '../../database/supabase';
import { ApiError } from '../../middleware/errorHandler';

export interface ConversationSummary {
  id: string;
  type: 'direct' | 'group';
  isPrivate: boolean;
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
  isPinned: boolean;
  pinnedAtMs: number | null;
  isArchived: boolean;
  archivedAtMs: number | null;
  mutedUntilMs: number | null;
  customSoundUri: string | null;
  customSoundLabel: string | null;
  markedUnread: boolean;
}

export class ConversationsService {
  constructor(private readonly db: DatabaseClient) {}

  async listForUser(userId: string, isPrivate = false): Promise<ConversationSummary[]> {
    const rows = isPrivate
      ? await this.db.listUserPrivateConversations(userId)
      : await this.db.listUserConversations(userId);
    return rows.map((r) => ({
      id: r.id,
      type: r.type,
      isPrivate,
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
      isPinned: Boolean(r.is_pinned),
      pinnedAtMs: r.pinned_at_ms ?? null,
      isArchived: Boolean(r.is_archived),
      archivedAtMs: r.archived_at_ms ?? null,
      mutedUntilMs: r.muted_until_ms ?? null,
      customSoundUri: r.custom_sound_uri ?? null,
      customSoundLabel: r.custom_sound_label ?? null,
      markedUnread: Boolean(r.marked_unread),
    }));
  }

  async getOrCreateDirectConversation(
    currentUserId: string,
    targetUserId: string,
    isPrivate = false
  ): Promise<ConversationSummary> {
    if (currentUserId === targetUserId) {
      throw new ApiError(400, 'invalid_target', 'Cannot start a chat with yourself.');
    }

    const exists = await this.db.isActiveUserExists(targetUserId);
    if (!exists) {
      throw new ApiError(404, 'account_not_found', 'Target user does not exist.');
    }

    const pair = [currentUserId, targetUserId].sort();
    const directKey = isPrivate ? `priv:${pair[0]}:${pair[1]}` : `${pair[0]}:${pair[1]}`;
    const convId = await this.db.getOrCreateDirectConversation(
      currentUserId,
      targetUserId,
      crypto.randomUUID(),
      directKey,
      Date.now(),
      isPrivate
    );

    const all = await this.listForUser(currentUserId, isPrivate);
    const match = all.find((c) => c.id === convId);
    if (!match) {
      throw new ApiError(500, 'conversation_create_failed', 'Failed to load conversation.');
    }
    return match;
  }

  async updateConversationPreferences(
    userId: string,
    conversationId: string,
    input: {
      isPinned?: boolean;
      isArchived?: boolean;
      mutedUntilMs?: number | null;
      customSoundUri?: string | null;
      customSoundLabel?: string | null;
      markedUnread?: boolean;
    },
    isPrivateScope = false
  ): Promise<{ ok: true }> {
    await this.assertMember(conversationId, userId, isPrivateScope);
    const now = Date.now();
    await this.db.upsertUserConversationSettings({
      userId,
      conversationId,
      nowMs: now,
      isPinned: input.isPinned,
      pinnedAtMs: input.isPinned === true ? now : input.isPinned === false ? null : undefined,
      isArchived: input.isArchived,
      archivedAtMs: input.isArchived === true ? now : input.isArchived === false ? null : undefined,
      mutedUntilMs: input.mutedUntilMs,
      customSoundUri: input.customSoundUri?.slice(0, 500) ?? input.customSoundUri,
      customSoundLabel: input.customSoundLabel?.slice(0, 80) ?? input.customSoundLabel,
      markedUnread: input.markedUnread,
    });
    return { ok: true };
  }

  async clearConversationForUser(
    userId: string,
    conversationId: string,
    isPrivateScope = false
  ): Promise<{ clearedAtMs: number }> {
    await this.assertMember(conversationId, userId, isPrivateScope);
    const now = Date.now();
    await this.db.upsertUserConversationSettings({
      userId,
      conversationId,
      nowMs: now,
      clearedAtMs: now,
      markedUnread: false,
    });
    return { clearedAtMs: now };
  }

  async deleteConversationForUser(
    userId: string,
    conversationId: string,
    isPrivateScope = false
  ): Promise<{ deletedAtMs: number }> {
    await this.assertMember(conversationId, userId, isPrivateScope);
    const now = Date.now();
    await this.db.upsertUserConversationSettings({
      userId,
      conversationId,
      nowMs: now,
      deletedAtMs: now,
      isPinned: false,
      pinnedAtMs: null,
      isArchived: false,
      archivedAtMs: null,
      markedUnread: false,
    });
    return { deletedAtMs: now };
  }

  async getPeerProfile(
    userId: string,
    conversationId: string,
    isPrivateScope = false
  ) {
    await this.assertMember(conversationId, userId, isPrivateScope);
    const peer = await this.db.getConversationPeerProfile(conversationId, userId);
    if (!peer) {
      throw new ApiError(404, 'account_not_found', 'Peer profile not found.');
    }
    return {
      user: {
        id: peer.id,
        displayName: peer.display_name,
        about: peer.about_text,
        avatarMediaId: peer.avatar_media_id,
        isOnline: peer.is_online,
        lastSeenMs: peer.last_seen_ms,
      },
      isBlockedByMe: peer.blocked_by_me,
      hasBlockedMe: peer.blocked_me,
    };
  }

  async assertMember(
    conversationId: string,
    userId: string,
    expectedPrivate?: boolean
  ): Promise<string[]> {
    const members = await this.db.listConversationMemberIds(conversationId);
    if (!members.includes(userId)) {
      throw new ApiError(403, 'forbidden_conversation', 'You are not a member of this conversation.');
    }

    if (expectedPrivate !== undefined) {
      const isPrivate = await this.db.isConversationPrivate(conversationId);
      if (isPrivate === null) {
        throw new ApiError(404, 'conversation_not_found', 'Conversation not found.');
      }
      if (!expectedPrivate && isPrivate) {
        throw new ApiError(
          403,
          'private_chat_locked',
          'Private conversations cannot be accessed through regular chat endpoints.'
        );
      }
      if (expectedPrivate && !isPrivate) {
        throw new ApiError(
          403,
          'forbidden_conversation',
          'Regular conversations cannot be accessed through private chat endpoints.'
        );
      }
    }

    return members;
  }
}
