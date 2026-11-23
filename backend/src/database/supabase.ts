import { Env } from '../config/env';

export class UniqueViolationError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'UniqueViolationError';
  }
}

export interface ActiveSessionVerification {
  session_id: string;
  revoked_at_ms: number | null;
  account_status: string;
  user_role: 'user' | 'owner';
}

export interface UserAccountRow {
  id: string;
  phone_e164: string;
  country_iso2: string;
  calling_code: string;
  pin_hash: string;
  role: 'user' | 'owner';
  failed_login_count: number;
  locked_until_ms: number | null;
  account_status: string;
  recovery_code_hash?: string | null;
  recovery_code_updated_at_ms?: number | null;
  recovery_failed_count?: number;
  recovery_locked_until_ms?: number | null;
}

export interface UserPrivatePinRow {
  id: string;
  pin_hash: string;
  failed_login_count: number;
  locked_until_ms: number | null;
  account_status: string;
  private_pin_hash: string | null;
  private_pin_failed_count: number;
  private_pin_locked_until_ms: number | null;
  private_pin_updated_at_ms: number | null;
}

export interface UserProfileBasicRow {
  display_name: string;
  about_text: string;
  avatar_media_id: string | null;
}

export interface OwnProfileRow {
  id: string;
  phone_e164: string;
  display_name: string;
  about_text: string;
  avatar_media_id: string | null;
  is_online: boolean;
  last_seen_ms: number | null;
}

export interface SessionRecordRow {
  id: string;
  user_id: string;
  device_id: string;
  refresh_token_hash: string;
  expires_at_ms: number;
  revoked_at_ms: number | null;
}

export interface ActiveSessionRow {
  session_id: string;
  device_id: string;
  device_name: string;
  platform: string;
  app_version: string;
  last_used_at_ms: number;
  created_at_ms: number;
}

export interface PublicUserSearchRow {
  id: string;
  display_name: string;
  about_text: string;
  avatar_media_id: string | null;
  is_online: boolean;
  last_seen_ms: number | null;
}

export interface ConversationQueryRow {
  id: string;
  type: 'direct' | 'group';
  last_message_id: string | null;
  last_message_preview: string | null;
  last_message_type: string | null;
  last_message_sender_id: string | null;
  last_message_at_ms: number | null;
  unread_count: number;
  updated_at_ms: number;
  peer_user_id: string;
  peer_display_name: string;
  peer_avatar_media_id: string | null;
  peer_about_text: string;
  peer_is_online: boolean;
  peer_last_seen_ms: number | null;
  blocked_by_me: boolean;
  blocked_me: boolean;
  is_pinned?: boolean;
  pinned_at_ms?: number | null;
  is_archived?: boolean;
  archived_at_ms?: number | null;
  muted_until_ms?: number | null;
  custom_sound_uri?: string | null;
  custom_sound_label?: string | null;
  marked_unread?: boolean;
}

export interface CallHistoryQueryRow {
  call_id: string;
  conversation_id: string;
  caller_id: string;
  peer_user_id: string;
  peer_display_name: string;
  peer_avatar_media_id: string | null;
  call_type: 'audio' | 'video';
  status: 'ringing' | 'accepted' | 'rejected' | 'missed' | 'ended' | 'failed';
  direction: 'incoming' | 'outgoing';
  started_at_ms: number;
  answered_at_ms: number | null;
  ended_at_ms: number | null;
  duration_seconds: number;
}

export interface NotificationEndpointRow {
  id: string;
  user_id: string;
  device_id: string;
  transport: string;
  endpoint_url: string | null;
  updated_at_ms: number;
}

export interface MessageQueryRow {
  id: string;
  conversation_id: string;
  sender_id: string;
  client_message_id: string;
  message_type: 'text' | 'image' | 'video' | 'voice' | 'audio' | 'file' | 'call_log';
  body_text: string | null;
  media_id: string | null;
  reply_to_message_id: string | null;
  deleted_for_all_at_ms: number | null;
  created_at_ms: number;
  media_category: string | null;
  original_filename: string | null;
  mime_type: string | null;
  size_bytes: number | null;
  duration_ms: number | null;
  width: number | null;
  height: number | null;
  delivered_at_ms: number | null;
  read_at_ms: number | null;
}

export interface MediaObjectRow {
  id: string;
  owner_user_id: string;
  conversation_id: string | null;
  category: string;
  storage_bucket: string;
  storage_path: string;
  original_filename: string;
  mime_type: string;
  size_bytes: number;
  upload_status: string;
}

export interface BlockedUserRow {
  user_id: string;
  display_name: string;
  avatar_media_id: string | null;
  blocked_at_ms: number;
}

export interface OwnerTrackedUserDbRow {
  user_id: string;
  display_name: string;
  country_iso2: string;
  phone_e164: string;
  is_online: boolean;
  last_seen_ms: number | null;
  created_at_ms: number;
  latest_device_name: string | null;
  latest_platform: string | null;
  latest_app_version: string | null;
  active_session_count: number;
}

export interface OwnerSnapshotDbResult {
  summary: {
    total_registered_users: number;
    currently_online_users: number;
    active_last_24h_users: number;
    active_sessions_count: number;
  };
  total_count: number;
  users: OwnerTrackedUserDbRow[];
}

export interface DatabaseClient {
  consumeRateLimit(
    bucketKey: string,
    maxRequests: number,
    windowMs: number,
    blockMs: number,
    nowMs: number
  ): Promise<{ allowed: boolean; retryAfterSeconds: number }>;

  verifyActiveSession(sessionId: string, userId: string): Promise<ActiveSessionVerification | null>;

  findActiveUserByPhone(phoneE164: string): Promise<UserAccountRow | null>;

  findAnyUserByPhone(phoneE164: string): Promise<{ id: string } | null>;

  registerUserAccount(input: {
    userId: string;
    phoneE164: string;
    countryIso2: string;
    callingCode: string;
    pinHash: string;
    displayName: string;
    deviceId: string;
    deviceName: string;
    appVersion: string;
    sessionId: string;
    refreshHash: string;
    expiresAtMs: number;
    nowMs: number;
  }): Promise<void>;

  recordFailedLogin(
    userId: string,
    failedCount: number,
    lockedUntilMs: number | null,
    nowMs: number
  ): Promise<void>;

  getUserProfileBasic(userId: string): Promise<UserProfileBasicRow | null>;

  completeLoginSession(input: {
    userId: string;
    deviceId: string;
    deviceName: string;
    appVersion: string;
    sessionId: string;
    refreshHash: string;
    expiresAtMs: number;
    nowMs: number;
  }): Promise<void>;

  getSessionById(sessionId: string): Promise<SessionRecordRow | null>;
  revokeSessionById(sessionId: string, userId: string | null, nowMs: number): Promise<void>;
  rotateRefreshSession(
    sessionId: string,
    refreshHash: string,
    expiresAtMs: number,
    lastUsedAtMs: number
  ): Promise<void>;
  listActiveUserSessions(userId: string, nowMs: number): Promise<ActiveSessionRow[]>;
  findSessionForRevoke(sessionId: string, userId: string): Promise<{ id: string } | null>;
  revokeAllUserSessions(userId: string, exceptSessionId: string | null, nowMs: number): Promise<void>;
  updateUserLoginPin(userId: string, pinHash: string, nowMs: number): Promise<void>;
  findUserRecoveryByPhone(phoneE164: string): Promise<UserAccountRow | null>;
  setRecoveryCodeHash(userId: string, recoveryCodeHash: string, nowMs: number): Promise<void>;
  recordFailedRecoveryAttempt(
    userId: string,
    failedCount: number,
    lockedUntilMs: number | null,
    nowMs: number
  ): Promise<void>;
  completeLoginPinRecovery(userId: string, newPinHash: string, nowMs: number): Promise<void>;

  searchPublicUserByPhone(
    requesterId: string,
    phoneE164: string,
    nameFilter?: string
  ): Promise<PublicUserSearchRow | null>;

  getOwnProfileDetail(userId: string): Promise<OwnProfileRow | null>;
  findCompletedProfileAvatarMedia(mediaId: string, userId: string): Promise<{ id: string } | null>;
  updateOwnProfile(
    userId: string,
    displayName: string,
    aboutText: string,
    avatarMediaId: string | null,
    nowMs: number
  ): Promise<void>;

  findActiveUserPinHash(userId: string): Promise<{ pin_hash: string } | null>;
  listUserMediaPaths(
    userId: string
  ): Promise<Array<{ id: string; storage_path: string; storage_bucket: string }>>;
  deleteUserAccountTx(userId: string, nowMs: number): Promise<void>;

  listUserConversations(userId: string): Promise<ConversationQueryRow[]>;
  listUserPrivateConversations(userId: string): Promise<ConversationQueryRow[]>;
  isConversationPrivate(conversationId: string): Promise<boolean | null>;
  getUserConversationPrivacyState(
    conversationId: string,
    userId: string
  ): Promise<{ isConversationPrivate: boolean; userMovedToPrivate: boolean; deletedAtMs: number | null } | null>;
  getMembersPrivateFlags(conversationId: string, memberIds: string[]): Promise<Record<string, boolean>>;
  findDirectConversationIdByKey(directKey: string): Promise<string | null>;
  isActiveUserExists(userId: string): Promise<boolean>;
  getOrCreateDirectConversation(
    currentUserId: string,
    targetUserId: string,
    newConvId: string,
    directKey: string,
    nowMs: number,
    isPrivate?: boolean
  ): Promise<string>;
  listConversationMemberIds(conversationId: string): Promise<string[]>;
  isBlockedBetween(userA: string, userB: string): Promise<boolean>;

  getUserPrivatePinState(userId: string): Promise<UserPrivatePinRow | null>;
  setPrivatePinHash(userId: string, privatePinHash: string, updatedAtMs: number): Promise<void>;
  recordFailedPrivatePinAttempt(
    userId: string,
    failedCount: number,
    lockedUntilMs: number | null,
    nowMs: number
  ): Promise<void>;
  clearPrivatePinLockout(userId: string, nowMs: number): Promise<void>;

  listConversationMessages(
    conversationId: string,
    beforeTs: number | null,
    beforeId: string | null,
    limit: number,
    userId?: string | null
  ): Promise<MessageQueryRow[]>;
  upsertUserConversationSettings(input: {
    userId: string;
    conversationId: string;
    nowMs: number;
    isPinned?: boolean;
    pinnedAtMs?: number | null;
    isArchived?: boolean;
    archivedAtMs?: number | null;
    mutedUntilMs?: number | null;
    customSoundUri?: string | null;
    customSoundLabel?: string | null;
    markedUnread?: boolean;
    clearedAtMs?: number | null;
    deletedAtMs?: number | null;
    movedToPrivate?: boolean;
    movedToPrivateAtMs?: number | null;
  }): Promise<void>;
  getConversationPeerProfile(
    conversationId: string,
    viewerUserId: string
  ): Promise<(PublicUserSearchRow & { blocked_by_me: boolean; blocked_me: boolean }) | null>;
  getSingleMessageDetail(messageId: string): Promise<MessageQueryRow | null>;
  findMessageByClientId(senderId: string, clientMessageId: string): Promise<{ id: string } | null>;
  findCompletedConversationMedia(
    mediaId: string
  ): Promise<{ id: string; owner_user_id: string; conversation_id: string | null } | null>;
  sendChatMessageTx(input: {
    messageId: string;
    conversationId: string;
    senderId: string;
    clientMessageId: string;
    messageType: string;
    bodyText: string | null;
    mediaId: string | null;
    replyToMessageId: string | null;
    preview: string;
    recipientIds: string[];
    nowMs: number;
  }): Promise<void>;
  markMessageReceiptsTx(
    conversationId: string,
    userId: string,
    messageIds: string[],
    type: 'delivered' | 'read',
    nowMs: number
  ): Promise<void>;
  findMessageForDelete(
    messageId: string
  ): Promise<{ id: string; conversation_id: string; sender_id: string; media_id: string | null } | null>;
  deleteOwnMessageTx(
    messageId: string,
    conversationId: string,
    mediaId: string | null,
    nowMs: number
  ): Promise<void>;

  insertMediaObject(input: {
    id: string;
    ownerUserId: string;
    conversationId: string | null;
    category: string;
    storageBucket: string;
    storagePath: string;
    originalFilename: string;
    mimeType: string;
    sizeBytes: number;
    durationMs: number | null;
    width: number | null;
    height: number | null;
    createdAtMs: number;
  }): Promise<void>;
  deleteMediaObjectRow(mediaId: string): Promise<void>;
  findMediaObjectById(mediaId: string): Promise<MediaObjectRow | null>;
  isUserBlockedBy(blockerId: string, blockedId: string): Promise<boolean>;

  initiateCallTx(input: {
    callId: string;
    conversationId: string;
    callerId: string;
    calleeId: string;
    callType: 'audio' | 'video';
    nowMs: number;
  }): Promise<void>;
  isCallParticipant(callId: string, userId: string): Promise<boolean>;
  findCallById(
    callId: string
  ): Promise<{ id: string; status: string; started_at_ms: number; answered_at_ms: number | null } | null>;
  acceptCallTx(callId: string, userId: string, nowMs: number): Promise<void>;
  finishCallTx(
    callId: string,
    userId: string,
    nextCallStatus: string,
    participantState: string,
    endedAtMs: number,
    durationSeconds: number
  ): Promise<void>;
  listCallParticipantIds(callId: string): Promise<string[]>;
  listUserCalls(userId: string, filter: string, limit: number): Promise<CallHistoryQueryRow[]>;

  listBlockedUsers(userId: string): Promise<BlockedUserRow[]>;
  blockUser(blockerId: string, targetUserId: string, nowMs: number): Promise<void>;
  unblockUser(blockerId: string, targetUserId: string): Promise<void>;
  insertReport(input: {
    id: string;
    reporterId: string;
    targetUserId: string;
    messageId: string | null;
    reason: string;
    details: string | null;
    createdAtMs: number;
  }): Promise<void>;
  upsertNotificationEndpoint(input: {
    id: string;
    userId: string;
    deviceId: string;
    transport: string;
    endpointUrl: string | null;
    updatedAtMs: number;
  }): Promise<void>;
  listNotificationEndpointsForUser(userId: string): Promise<NotificationEndpointRow[]>;
  deleteNotificationEndpoint(userId: string, deviceId: string): Promise<void>;

  updateUserPresence(userId: string, isOnline: boolean, lastSeenMs: number): Promise<void>;
  listPresencePeerIds(userId: string): Promise<string[]>;
  checkConversationPair(conversationId: string, userId: string, targetUserId: string): Promise<boolean>;

  getOwnerActivitySnapshot(input: {
    search: string | null;
    limit: number;
    offset: number;
    last24hCutoff: number;
    nowMs: number;
  }): Promise<OwnerSnapshotDbResult>;

  insertAuditLog(input: {
    id: string;
    userId: string | null;
    eventType: string;
    outcome: string;
    ipHash: string | null;
    metaJson: Record<string, string | number | boolean> | null;
    createdAtMs: number;
  }): Promise<void>;
}

/**
 * Edge-native Supabase PostgreSQL client over PostgREST + RPC REST APIs.
 * Uses Cloudflare Worker Secret `SUPABASE_SERVICE_ROLE_KEY` exclusively server-side.
 */
export class SupabaseDatabase implements DatabaseClient {
  private readonly restBase: string;

  constructor(
    supabaseUrl: string,
    private readonly serviceRoleKey: string
  ) {
    const normalized = supabaseUrl.trim().replace(/\/+$/, '');
    if (!normalized) {
      throw new Error('SUPABASE_URL secret/env is not configured.');
    }
    if (!serviceRoleKey.trim()) {
      throw new Error('SUPABASE_SERVICE_ROLE_KEY secret is not configured.');
    }
    this.restBase = `${normalized}/rest/v1`;
  }

  private headers(extra?: Record<string, string>): HeadersInit {
    const key = this.serviceRoleKey.trim();
    const base: Record<string, string> = {
      apikey: key,
      'Content-Type': 'application/json',
      ...extra,
    };
    if (key.split('.').length === 3) {
      base['Authorization'] = `Bearer ${key}`;
    }
    return base;
  }

  private async requestJson<T>(path: string, init: RequestInit): Promise<T> {
    const res = await fetch(`${this.restBase}${path}`, init);
    if (!res.ok) {
      const detail = await res.text().catch(() => '');
      if (res.status === 409 || detail.includes('"23505"') || detail.includes('duplicate_account')) {
        throw new UniqueViolationError(detail.slice(0, 240));
      }
      throw new Error(`Supabase PostgREST error ${res.status} on ${path}: ${detail.slice(0, 240)}`);
    }
    if (res.status === 204) {
      return undefined as T;
    }
    const text = await res.text();
    if (!text) return undefined as T;
    return JSON.parse(text) as T;
  }

  private async rpc<T>(fnName: string, params: Record<string, unknown>): Promise<T> {
    return this.requestJson<T>(`/rpc/${fnName}`, {
      method: 'POST',
      headers: this.headers(),
      body: JSON.stringify(params),
    });
  }

  private enc(value: string): string {
    return encodeURIComponent(value);
  }

  async consumeRateLimit(
    bucketKey: string,
    maxRequests: number,
    windowMs: number,
    blockMs: number,
    nowMs: number
  ): Promise<{ allowed: boolean; retryAfterSeconds: number }> {
    const rows = await this.rpc<Array<{ allowed: boolean; retry_after_seconds: number }>>(
      'consume_rate_limit',
      {
        p_bucket_key: bucketKey,
        p_max_requests: maxRequests,
        p_window_ms: windowMs,
        p_block_ms: blockMs,
        p_now_ms: nowMs,
      }
    );
    const first = rows[0];
    return {
      allowed: first ? Boolean(first.allowed) : true,
      retryAfterSeconds: first?.retry_after_seconds ?? 0,
    };
  }

  async verifyActiveSession(sessionId: string, userId: string): Promise<ActiveSessionVerification | null> {
    const rows = await this.rpc<ActiveSessionVerification[]>('verify_active_session', {
      p_session_id: sessionId,
      p_user_id: userId,
    });
    return rows[0] ?? null;
  }

  async findActiveUserByPhone(phoneE164: string): Promise<UserAccountRow | null> {
    const rows = await this.requestJson<UserAccountRow[]>(
      `/users?select=id,phone_e164,country_iso2,calling_code,pin_hash,role,failed_login_count,locked_until_ms,account_status&phone_e164=eq.${this.enc(phoneE164)}&account_status=eq.active&limit=1`,
      { method: 'GET', headers: this.headers() }
    );
    return rows[0] ?? null;
  }

  async findAnyUserByPhone(phoneE164: string): Promise<{ id: string } | null> {
    const rows = await this.requestJson<Array<{ id: string }>>(
      `/users?select=id&phone_e164=eq.${this.enc(phoneE164)}&limit=1`,
      { method: 'GET', headers: this.headers() }
    );
    return rows[0] ?? null;
  }

  async registerUserAccount(input: {
    userId: string;
    phoneE164: string;
    countryIso2: string;
    callingCode: string;
    pinHash: string;
    displayName: string;
    deviceId: string;
    deviceName: string;
    appVersion: string;
    sessionId: string;
    refreshHash: string;
    expiresAtMs: number;
    nowMs: number;
  }): Promise<void> {
    await this.rpc<null>('register_user_account', {
      p_user_id: input.userId,
      p_phone_e164: input.phoneE164,
      p_country_iso2: input.countryIso2,
      p_calling_code: input.callingCode,
      p_pin_hash: input.pinHash,
      p_display_name: input.displayName,
      p_device_id: input.deviceId,
      p_device_name: input.deviceName,
      p_app_version: input.appVersion,
      p_session_id: input.sessionId,
      p_refresh_hash: input.refreshHash,
      p_expires_at_ms: input.expiresAtMs,
      p_now_ms: input.nowMs,
    });
  }

  async recordFailedLogin(
    userId: string,
    failedCount: number,
    lockedUntilMs: number | null,
    nowMs: number
  ): Promise<void> {
    await this.requestJson<unknown>(`/users?id=eq.${this.enc(userId)}`, {
      method: 'PATCH',
      headers: this.headers({ Prefer: 'return=minimal' }),
      body: JSON.stringify({
        failed_login_count: failedCount,
        locked_until_ms: lockedUntilMs,
        last_failed_login_ms: nowMs,
        updated_at_ms: nowMs,
      }),
    });
  }

  async getUserProfileBasic(userId: string): Promise<UserProfileBasicRow | null> {
    const rows = await this.requestJson<UserProfileBasicRow[]>(
      `/user_profiles?select=display_name,about_text,avatar_media_id&user_id=eq.${this.enc(userId)}&limit=1`,
      { method: 'GET', headers: this.headers() }
    );
    return rows[0] ?? null;
  }

  async completeLoginSession(input: {
    userId: string;
    deviceId: string;
    deviceName: string;
    appVersion: string;
    sessionId: string;
    refreshHash: string;
    expiresAtMs: number;
    nowMs: number;
  }): Promise<void> {
    await this.rpc<null>('complete_login_session', {
      p_user_id: input.userId,
      p_device_id: input.deviceId,
      p_device_name: input.deviceName,
      p_app_version: input.appVersion,
      p_session_id: input.sessionId,
      p_refresh_hash: input.refreshHash,
      p_expires_at_ms: input.expiresAtMs,
      p_now_ms: input.nowMs,
    });
  }

  async getSessionById(sessionId: string): Promise<SessionRecordRow | null> {
    const rows = await this.requestJson<SessionRecordRow[]>(
      `/sessions?select=id,user_id,device_id,refresh_token_hash,expires_at_ms,revoked_at_ms&id=eq.${this.enc(sessionId)}&limit=1`,
      { method: 'GET', headers: this.headers() }
    );
    return rows[0] ?? null;
  }

  async revokeSessionById(sessionId: string, userId: string | null, nowMs: number): Promise<void> {
    const userFilter = userId ? `&user_id=eq.${this.enc(userId)}` : '';
    await this.requestJson<unknown>(`/sessions?id=eq.${this.enc(sessionId)}${userFilter}`, {
      method: 'PATCH',
      headers: this.headers({ Prefer: 'return=minimal' }),
      body: JSON.stringify({ revoked_at_ms: nowMs }),
    });
  }

  async rotateRefreshSession(
    sessionId: string,
    refreshHash: string,
    expiresAtMs: number,
    lastUsedAtMs: number
  ): Promise<void> {
    await this.requestJson<unknown>(`/sessions?id=eq.${this.enc(sessionId)}`, {
      method: 'PATCH',
      headers: this.headers({ Prefer: 'return=minimal' }),
      body: JSON.stringify({
        refresh_token_hash: refreshHash,
        expires_at_ms: expiresAtMs,
        last_used_at_ms: lastUsedAtMs,
      }),
    });
  }

  async listActiveUserSessions(userId: string, nowMs: number): Promise<ActiveSessionRow[]> {
    return this.rpc<ActiveSessionRow[]>('list_active_user_sessions', {
      p_user_id: userId,
      p_now_ms: nowMs,
    });
  }

  async findSessionForRevoke(sessionId: string, userId: string): Promise<{ id: string } | null> {
    const rows = await this.requestJson<Array<{ id: string }>>(
      `/sessions?select=id&id=eq.${this.enc(sessionId)}&user_id=eq.${this.enc(userId)}&revoked_at_ms=is.null&limit=1`,
      { method: 'GET', headers: this.headers() }
    );
    return rows[0] ?? null;
  }

  async revokeAllUserSessions(
    userId: string,
    exceptSessionId: string | null,
    nowMs: number
  ): Promise<void> {
    const exceptFilter = exceptSessionId ? `&id=neq.${this.enc(exceptSessionId)}` : '';
    await this.requestJson<unknown>(
      `/sessions?user_id=eq.${this.enc(userId)}&revoked_at_ms=is.null${exceptFilter}`,
      {
        method: 'PATCH',
        headers: this.headers({ Prefer: 'return=minimal' }),
        body: JSON.stringify({ revoked_at_ms: nowMs }),
      }
    );
  }

  async updateUserLoginPin(userId: string, pinHash: string, nowMs: number): Promise<void> {
    await this.requestJson<unknown>(`/users?id=eq.${this.enc(userId)}`, {
      method: 'PATCH',
      headers: this.headers({ Prefer: 'return=minimal' }),
      body: JSON.stringify({
        pin_hash: pinHash,
        pin_algo: 'pbkdf2_sha512_v1',
        pin_updated_at_ms: nowMs,
        failed_login_count: 0,
        locked_until_ms: null,
        updated_at_ms: nowMs,
      }),
    });
  }

  async findUserRecoveryByPhone(phoneE164: string): Promise<UserAccountRow | null> {
    const rows = await this.requestJson<UserAccountRow[]>(
      `/users?select=id,phone_e164,country_iso2,calling_code,pin_hash,role,failed_login_count,locked_until_ms,account_status,recovery_code_hash,recovery_code_updated_at_ms,recovery_failed_count,recovery_locked_until_ms&phone_e164=eq.${this.enc(phoneE164)}&account_status=eq.active&limit=1`,
      { method: 'GET', headers: this.headers() }
    );
    return rows[0] ?? null;
  }

  async setRecoveryCodeHash(userId: string, recoveryCodeHash: string, nowMs: number): Promise<void> {
    await this.requestJson<unknown>(`/users?id=eq.${this.enc(userId)}`, {
      method: 'PATCH',
      headers: this.headers({ Prefer: 'return=minimal' }),
      body: JSON.stringify({
        recovery_code_hash: recoveryCodeHash,
        recovery_code_algo: 'pbkdf2_sha512_v1',
        recovery_code_updated_at_ms: nowMs,
        recovery_failed_count: 0,
        recovery_locked_until_ms: null,
        updated_at_ms: nowMs,
      }),
    });
  }

  async recordFailedRecoveryAttempt(
    userId: string,
    failedCount: number,
    lockedUntilMs: number | null,
    nowMs: number
  ): Promise<void> {
    await this.requestJson<unknown>(`/users?id=eq.${this.enc(userId)}`, {
      method: 'PATCH',
      headers: this.headers({ Prefer: 'return=minimal' }),
      body: JSON.stringify({
        recovery_failed_count: failedCount,
        recovery_locked_until_ms: lockedUntilMs,
        updated_at_ms: nowMs,
      }),
    });
  }

  async completeLoginPinRecovery(userId: string, newPinHash: string, nowMs: number): Promise<void> {
    await this.requestJson<unknown>(`/users?id=eq.${this.enc(userId)}`, {
      method: 'PATCH',
      headers: this.headers({ Prefer: 'return=minimal' }),
      body: JSON.stringify({
        pin_hash: newPinHash,
        pin_algo: 'pbkdf2_sha512_v1',
        pin_updated_at_ms: nowMs,
        failed_login_count: 0,
        locked_until_ms: null,
        recovery_failed_count: 0,
        recovery_locked_until_ms: null,
        updated_at_ms: nowMs,
      }),
    });
  }

  async searchPublicUserByPhone(
    requesterId: string,
    phoneE164: string,
    nameFilter?: string
  ): Promise<PublicUserSearchRow | null> {
    const rows = await this.rpc<PublicUserSearchRow[]>('search_public_user_by_phone', {
      p_requester_id: requesterId,
      p_phone_e164: phoneE164,
      p_name_filter: nameFilter ?? null,
    });
    return rows[0] ?? null;
  }

  async getOwnProfileDetail(userId: string): Promise<OwnProfileRow | null> {
    const rows = await this.requestJson<
      Array<{
        id: string;
        phone_e164: string;
        user_profiles: OwnProfileRow | OwnProfileRow[] | null;
      }>
    >(
      `/users?select=id,phone_e164,user_profiles(display_name,about_text,avatar_media_id,is_online,last_seen_ms)&id=eq.${this.enc(userId)}&account_status=eq.active&limit=1`,
      { method: 'GET', headers: this.headers() }
    );
    const u = rows[0];
    if (!u) return null;
    const prof = Array.isArray(u.user_profiles) ? u.user_profiles[0] : u.user_profiles;
    if (!prof) {
      const now = Date.now();
      const defaultName = `User ${u.phone_e164.slice(-4)}`;
      const defaultAbout = 'Available on MY CHATTING PRO';
      await this.requestJson<unknown>('/user_profiles?on_conflict=user_id', {
        method: 'POST',
        headers: this.headers({ Prefer: 'resolution=ignore-duplicates,return=minimal' }),
        body: JSON.stringify({
          user_id: u.id,
          display_name: defaultName,
          about_text: defaultAbout,
          is_online: false,
          last_seen_ms: now,
          updated_at_ms: now,
        }),
      }).catch(() => undefined);
      return {
        id: u.id,
        phone_e164: u.phone_e164,
        display_name: defaultName,
        about_text: defaultAbout,
        avatar_media_id: null,
        is_online: false,
        last_seen_ms: now,
      };
    }
    return {
      id: u.id,
      phone_e164: u.phone_e164,
      display_name: prof.display_name,
      about_text: prof.about_text,
      avatar_media_id: prof.avatar_media_id,
      is_online: Boolean(prof.is_online),
      last_seen_ms: prof.last_seen_ms,
    };
  }

  async findCompletedProfileAvatarMedia(mediaId: string, userId: string): Promise<{ id: string } | null> {
    const rows = await this.requestJson<Array<{ id: string }>>(
      `/media_objects?select=id&id=eq.${this.enc(mediaId)}&owner_user_id=eq.${this.enc(userId)}&category=eq.profile&upload_status=eq.completed&limit=1`,
      { method: 'GET', headers: this.headers() }
    );
    return rows[0] ?? null;
  }

  async updateOwnProfile(
    userId: string,
    displayName: string,
    aboutText: string,
    avatarMediaId: string | null,
    nowMs: number
  ): Promise<void> {
    await this.requestJson<unknown>(`/user_profiles?user_id=eq.${this.enc(userId)}`, {
      method: 'PATCH',
      headers: this.headers({ Prefer: 'return=minimal' }),
      body: JSON.stringify({
        display_name: displayName,
        about_text: aboutText,
        avatar_media_id: avatarMediaId,
        updated_at_ms: nowMs,
      }),
    });
  }

  async findActiveUserPinHash(userId: string): Promise<{ pin_hash: string } | null> {
    const rows = await this.requestJson<Array<{ pin_hash: string }>>(
      `/users?select=pin_hash&id=eq.${this.enc(userId)}&account_status=eq.active&limit=1`,
      { method: 'GET', headers: this.headers() }
    );
    return rows[0] ?? null;
  }

  async listUserMediaPaths(
    userId: string
  ): Promise<Array<{ id: string; storage_path: string; storage_bucket: string }>> {
    return this.requestJson<Array<{ id: string; storage_path: string; storage_bucket: string }>>(
      `/media_objects?select=id,storage_path,storage_bucket&owner_user_id=eq.${this.enc(userId)}`,
      { method: 'GET', headers: this.headers() }
    );
  }

  async deleteUserAccountTx(userId: string, nowMs: number): Promise<void> {
    await this.rpc<null>('delete_user_account_tx', {
      p_user_id: userId,
      p_now_ms: nowMs,
    });
  }

  async listUserConversations(userId: string): Promise<ConversationQueryRow[]> {
    return this.rpc<ConversationQueryRow[]>('list_user_conversations', { p_user_id: userId });
  }

  async listUserPrivateConversations(userId: string): Promise<ConversationQueryRow[]> {
    return this.rpc<ConversationQueryRow[]>('list_user_private_conversations', { p_user_id: userId });
  }

  async isConversationPrivate(conversationId: string): Promise<boolean | null> {
    const rows = await this.requestJson<Array<{ is_private?: boolean }>>(
      `/conversations?select=is_private&id=eq.${this.enc(conversationId)}&limit=1`,
      { method: 'GET', headers: this.headers() }
    );
    if (!rows[0]) return null;
    return Boolean(rows[0].is_private);
  }

  async getUserConversationPrivacyState(
    conversationId: string,
    userId: string
  ): Promise<{ isConversationPrivate: boolean; userMovedToPrivate: boolean; deletedAtMs: number | null } | null> {
    const [convRows, ucsRows] = await Promise.all([
      this.requestJson<Array<{ is_private?: boolean }>>(
        `/conversations?select=is_private&id=eq.${this.enc(conversationId)}&limit=1`,
        { method: 'GET', headers: this.headers() }
      ),
      this.requestJson<Array<{ moved_to_private?: boolean; deleted_at_ms?: number | null }>>(
        `/user_conversation_settings?select=moved_to_private,deleted_at_ms&user_id=eq.${this.enc(userId)}&conversation_id=eq.${this.enc(conversationId)}&limit=1`,
        { method: 'GET', headers: this.headers() }
      ),
    ]);
    if (!convRows[0]) return null;
    const ucs = ucsRows[0];
    return {
      isConversationPrivate: Boolean(convRows[0].is_private),
      userMovedToPrivate: Boolean(ucs?.moved_to_private),
      deletedAtMs: ucs?.deleted_at_ms ?? null,
    };
  }

  async getMembersPrivateFlags(conversationId: string, memberIds: string[]): Promise<Record<string, boolean>> {
    const flags: Record<string, boolean> = {};
    if (memberIds.length === 0) return flags;
    const isConvPrivate = await this.isConversationPrivate(conversationId);
    if (isConvPrivate) {
      for (const id of memberIds) {
        flags[id] = true;
      }
      return flags;
    }

    const ucsRows = await this.requestJson<Array<{ user_id: string; moved_to_private?: boolean }>>(
      `/user_conversation_settings?select=user_id,moved_to_private&conversation_id=eq.${this.enc(conversationId)}`,
      { method: 'GET', headers: this.headers() }
    );
    const movedMap = new Map<string, boolean>();
    for (const row of ucsRows) {
      movedMap.set(row.user_id, Boolean(row.moved_to_private));
    }
    for (const id of memberIds) {
      flags[id] = movedMap.get(id) ?? false;
    }
    return flags;
  }

  async findDirectConversationIdByKey(directKey: string): Promise<string | null> {
    const rows = await this.requestJson<Array<{ id: string }>>(
      `/conversations?select=id&direct_key=eq.${this.enc(directKey)}&limit=1`,
      { method: 'GET', headers: this.headers() }
    );
    return rows[0]?.id ?? null;
  }

  async isActiveUserExists(userId: string): Promise<boolean> {
    const rows = await this.requestJson<Array<{ id: string }>>(
      `/users?select=id&id=eq.${this.enc(userId)}&account_status=eq.active&limit=1`,
      { method: 'GET', headers: this.headers() }
    );
    return Boolean(rows[0]);
  }

  async getOrCreateDirectConversation(
    currentUserId: string,
    targetUserId: string,
    newConvId: string,
    directKey: string,
    nowMs: number,
    isPrivate = false
  ): Promise<string> {
    return this.rpc<string>('get_or_create_direct_conversation', {
      p_current_user_id: currentUserId,
      p_target_user_id: targetUserId,
      p_new_conv_id: newConvId,
      p_direct_key: directKey,
      p_now_ms: nowMs,
      p_is_private: isPrivate,
    });
  }

  async getUserPrivatePinState(userId: string): Promise<UserPrivatePinRow | null> {
    const rows = await this.requestJson<UserPrivatePinRow[]>(
      `/users?select=id,pin_hash,failed_login_count,locked_until_ms,account_status,private_pin_hash,private_pin_failed_count,private_pin_locked_until_ms,private_pin_updated_at_ms&id=eq.${this.enc(userId)}&account_status=eq.active&limit=1`,
      { method: 'GET', headers: this.headers() }
    );
    return rows[0] ?? null;
  }

  async setPrivatePinHash(userId: string, privatePinHash: string, updatedAtMs: number): Promise<void> {
    await this.requestJson<unknown>(`/users?id=eq.${this.enc(userId)}`, {
      method: 'PATCH',
      headers: this.headers({ Prefer: 'return=minimal' }),
      body: JSON.stringify({
        private_pin_hash: privatePinHash,
        private_pin_algo: 'pbkdf2_sha512_v1',
        private_pin_failed_count: 0,
        private_pin_locked_until_ms: null,
        private_pin_updated_at_ms: updatedAtMs,
        updated_at_ms: updatedAtMs,
      }),
    });
  }

  async recordFailedPrivatePinAttempt(
    userId: string,
    failedCount: number,
    lockedUntilMs: number | null,
    nowMs: number
  ): Promise<void> {
    await this.requestJson<unknown>(`/users?id=eq.${this.enc(userId)}`, {
      method: 'PATCH',
      headers: this.headers({ Prefer: 'return=minimal' }),
      body: JSON.stringify({
        private_pin_failed_count: failedCount,
        private_pin_locked_until_ms: lockedUntilMs,
        updated_at_ms: nowMs,
      }),
    });
  }

  async clearPrivatePinLockout(userId: string, nowMs: number): Promise<void> {
    await this.requestJson<unknown>(`/users?id=eq.${this.enc(userId)}`, {
      method: 'PATCH',
      headers: this.headers({ Prefer: 'return=minimal' }),
      body: JSON.stringify({
        private_pin_failed_count: 0,
        private_pin_locked_until_ms: null,
        updated_at_ms: nowMs,
      }),
    });
  }

  async listConversationMemberIds(conversationId: string): Promise<string[]> {
    const rows = await this.requestJson<Array<{ user_id: string }>>(
      `/conversation_members?select=user_id&conversation_id=eq.${this.enc(conversationId)}`,
      { method: 'GET', headers: this.headers() }
    );
    return rows.map((r) => r.user_id);
  }

  async isBlockedBetween(userA: string, userB: string): Promise<boolean> {
    const a = this.enc(userA);
    const b = this.enc(userB);
    const rows = await this.requestJson<Array<{ blocker_id: string }>>(
      `/blocked_users?select=blocker_id&or=(and(blocker_id.eq.${a},blocked_id.eq.${b}),and(blocker_id.eq.${b},blocked_id.eq.${a}))&limit=1`,
      { method: 'GET', headers: this.headers() }
    );
    return Boolean(rows[0]);
  }

  async listConversationMessages(
    conversationId: string,
    beforeTs: number | null,
    beforeId: string | null,
    limit: number,
    userId: string | null = null
  ): Promise<MessageQueryRow[]> {
    return this.rpc<MessageQueryRow[]>('list_conversation_messages', {
      p_conversation_id: conversationId,
      p_before_ts: beforeTs,
      p_before_id: beforeId,
      p_limit: limit,
      p_user_id: userId,
    });
  }

  async upsertUserConversationSettings(input: {
    userId: string;
    conversationId: string;
    nowMs: number;
    isPinned?: boolean;
    pinnedAtMs?: number | null;
    isArchived?: boolean;
    archivedAtMs?: number | null;
    mutedUntilMs?: number | null;
    customSoundUri?: string | null;
    customSoundLabel?: string | null;
    markedUnread?: boolean;
    clearedAtMs?: number | null;
    deletedAtMs?: number | null;
    movedToPrivate?: boolean;
    movedToPrivateAtMs?: number | null;
  }): Promise<void> {
    const existingRows = await this.requestJson<Array<Record<string, unknown>>>(
      `/user_conversation_settings?user_id=eq.${this.enc(input.userId)}&conversation_id=eq.${this.enc(input.conversationId)}&limit=1`,
      { method: 'GET', headers: this.headers() }
    );
    const existing = existingRows[0] ?? {};
    const merged: Record<string, unknown> = {
      user_id: input.userId,
      conversation_id: input.conversationId,
      is_pinned: input.isPinned !== undefined ? input.isPinned : Boolean(existing.is_pinned),
      pinned_at_ms: input.pinnedAtMs !== undefined ? input.pinnedAtMs : (existing.pinned_at_ms ?? null),
      is_archived: input.isArchived !== undefined ? input.isArchived : Boolean(existing.is_archived),
      archived_at_ms:
        input.archivedAtMs !== undefined ? input.archivedAtMs : (existing.archived_at_ms ?? null),
      muted_until_ms:
        input.mutedUntilMs !== undefined ? input.mutedUntilMs : (existing.muted_until_ms ?? null),
      custom_sound_uri:
        input.customSoundUri !== undefined
          ? input.customSoundUri
          : (existing.custom_sound_uri ?? null),
      custom_sound_label:
        input.customSoundLabel !== undefined
          ? input.customSoundLabel
          : (existing.custom_sound_label ?? null),
      marked_unread:
        input.markedUnread !== undefined ? input.markedUnread : Boolean(existing.marked_unread),
      cleared_at_ms:
        input.clearedAtMs !== undefined ? input.clearedAtMs : (existing.cleared_at_ms ?? null),
      deleted_at_ms:
        input.deletedAtMs !== undefined ? input.deletedAtMs : (existing.deleted_at_ms ?? null),
      moved_to_private:
        input.movedToPrivate !== undefined ? input.movedToPrivate : Boolean(existing.moved_to_private),
      moved_to_private_at_ms:
        input.movedToPrivateAtMs !== undefined
          ? input.movedToPrivateAtMs
          : (existing.moved_to_private_at_ms ?? null),
      updated_at_ms: input.nowMs,
    };

    await this.requestJson<unknown>(
      '/user_conversation_settings?on_conflict=user_id,conversation_id',
      {
        method: 'POST',
        headers: this.headers({ Prefer: 'resolution=merge-duplicates,return=minimal' }),
        body: JSON.stringify(merged),
      }
    );

    if (input.markedUnread === false) {
      await this.requestJson<unknown>(
        `/conversation_members?conversation_id=eq.${this.enc(input.conversationId)}&user_id=eq.${this.enc(input.userId)}`,
        {
          method: 'PATCH',
          headers: this.headers({ Prefer: 'return=minimal' }),
          body: JSON.stringify({ unread_count: 0 }),
        }
      );
    }
  }

  async getConversationPeerProfile(
    conversationId: string,
    viewerUserId: string
  ): Promise<(PublicUserSearchRow & { blocked_by_me: boolean; blocked_me: boolean }) | null> {
    const members = await this.listConversationMemberIds(conversationId);
    if (!members.includes(viewerUserId)) return null;
    const peerId = members.find((m) => m !== viewerUserId);
    if (!peerId) return null;

    const users = await this.requestJson<
      Array<{
        id: string;
        account_status: string;
        user_profiles: PublicUserSearchRow | PublicUserSearchRow[] | null;
      }>
    >(
      `/users?select=id,account_status,user_profiles(display_name,about_text,avatar_media_id,is_online,last_seen_ms)&id=eq.${this.enc(peerId)}&account_status=eq.active&limit=1`,
      { method: 'GET', headers: this.headers() }
    );
    const u = users[0];
    if (!u) return null;
    const p = Array.isArray(u.user_profiles) ? u.user_profiles[0] : u.user_profiles;
    const blockedByMe = await this.isUserBlockedBy(viewerUserId, peerId);
    const blockedMe = await this.isUserBlockedBy(peerId, viewerUserId);
    return {
      id: peerId,
      display_name: p?.display_name ?? 'MY CHATTING PRO User',
      about_text: blockedMe ? '' : (p?.about_text ?? ''),
      avatar_media_id: blockedMe ? null : (p?.avatar_media_id ?? null),
      is_online: blockedMe ? false : Boolean(p?.is_online),
      last_seen_ms: blockedMe ? null : (p?.last_seen_ms ?? null),
      blocked_by_me: blockedByMe,
      blocked_me: blockedMe,
    };
  }

  async getSingleMessageDetail(messageId: string): Promise<MessageQueryRow | null> {
    const rows = await this.rpc<MessageQueryRow[]>('get_single_message_detail', {
      p_message_id: messageId,
    });
    return rows[0] ?? null;
  }

  async findMessageByClientId(senderId: string, clientMessageId: string): Promise<{ id: string } | null> {
    const rows = await this.requestJson<Array<{ id: string }>>(
      `/messages?select=id&sender_id=eq.${this.enc(senderId)}&client_message_id=eq.${this.enc(clientMessageId)}&limit=1`,
      { method: 'GET', headers: this.headers() }
    );
    return rows[0] ?? null;
  }

  async findCompletedConversationMedia(
    mediaId: string
  ): Promise<{ id: string; owner_user_id: string; conversation_id: string | null } | null> {
    const rows = await this.requestJson<
      Array<{ id: string; owner_user_id: string; conversation_id: string | null }>
    >(
      `/media_objects?select=id,owner_user_id,conversation_id&id=eq.${this.enc(mediaId)}&upload_status=eq.completed&limit=1`,
      { method: 'GET', headers: this.headers() }
    );
    return rows[0] ?? null;
  }

  async sendChatMessageTx(input: {
    messageId: string;
    conversationId: string;
    senderId: string;
    clientMessageId: string;
    messageType: string;
    bodyText: string | null;
    mediaId: string | null;
    replyToMessageId: string | null;
    preview: string;
    recipientIds: string[];
    nowMs: number;
  }): Promise<void> {
    await this.rpc<null>('send_chat_message', {
      p_message_id: input.messageId,
      p_conversation_id: input.conversationId,
      p_sender_id: input.senderId,
      p_client_message_id: input.clientMessageId,
      p_message_type: input.messageType,
      p_body_text: input.bodyText,
      p_media_id: input.mediaId,
      p_reply_to_message_id: input.replyToMessageId,
      p_preview: input.preview,
      p_recipient_ids: input.recipientIds,
      p_now_ms: input.nowMs,
    });
  }

  async markMessageReceiptsTx(
    conversationId: string,
    userId: string,
    messageIds: string[],
    type: 'delivered' | 'read',
    nowMs: number
  ): Promise<void> {
    await this.rpc<null>('mark_message_receipts', {
      p_conversation_id: conversationId,
      p_user_id: userId,
      p_message_ids: messageIds,
      p_type: type,
      p_now_ms: nowMs,
    });
  }

  async findMessageForDelete(
    messageId: string
  ): Promise<{ id: string; conversation_id: string; sender_id: string; media_id: string | null } | null> {
    const rows = await this.requestJson<
      Array<{ id: string; conversation_id: string; sender_id: string; media_id: string | null }>
    >(
      `/messages?select=id,conversation_id,sender_id,media_id&id=eq.${this.enc(messageId)}&limit=1`,
      { method: 'GET', headers: this.headers() }
    );
    return rows[0] ?? null;
  }

  async deleteOwnMessageTx(
    messageId: string,
    conversationId: string,
    mediaId: string | null,
    nowMs: number
  ): Promise<void> {
    await this.rpc<null>('delete_own_message', {
      p_message_id: messageId,
      p_conversation_id: conversationId,
      p_media_id: mediaId,
      p_now_ms: nowMs,
    });
  }

  async insertMediaObject(input: {
    id: string;
    ownerUserId: string;
    conversationId: string | null;
    category: string;
    storageBucket: string;
    storagePath: string;
    originalFilename: string;
    mimeType: string;
    sizeBytes: number;
    durationMs: number | null;
    width: number | null;
    height: number | null;
    createdAtMs: number;
  }): Promise<void> {
    await this.requestJson<unknown>('/media_objects', {
      method: 'POST',
      headers: this.headers({ Prefer: 'return=minimal' }),
      body: JSON.stringify({
        id: input.id,
        owner_user_id: input.ownerUserId,
        conversation_id: input.conversationId,
        category: input.category,
        storage_bucket: input.storageBucket,
        storage_path: input.storagePath,
        original_filename: input.originalFilename,
        mime_type: input.mimeType,
        size_bytes: input.sizeBytes,
        duration_ms: input.durationMs,
        width: input.width,
        height: input.height,
        upload_status: 'completed',
        created_at_ms: input.createdAtMs,
      }),
    });
  }

  async deleteMediaObjectRow(mediaId: string): Promise<void> {
    await this.requestJson<unknown>(`/media_objects?id=eq.${this.enc(mediaId)}`, {
      method: 'DELETE',
      headers: this.headers({ Prefer: 'return=minimal' }),
    });
  }

  async findMediaObjectById(mediaId: string): Promise<MediaObjectRow | null> {
    const rows = await this.requestJson<MediaObjectRow[]>(
      `/media_objects?select=id,owner_user_id,conversation_id,category,storage_bucket,storage_path,original_filename,mime_type,size_bytes,upload_status&id=eq.${this.enc(mediaId)}&limit=1`,
      { method: 'GET', headers: this.headers() }
    );
    return rows[0] ?? null;
  }

  async isUserBlockedBy(blockerId: string, blockedId: string): Promise<boolean> {
    const rows = await this.requestJson<Array<{ blocker_id: string }>>(
      `/blocked_users?select=blocker_id&blocker_id=eq.${this.enc(blockerId)}&blocked_id=eq.${this.enc(blockedId)}&limit=1`,
      { method: 'GET', headers: this.headers() }
    );
    return Boolean(rows[0]);
  }

  async initiateCallTx(input: {
    callId: string;
    conversationId: string;
    callerId: string;
    calleeId: string;
    callType: 'audio' | 'video';
    nowMs: number;
  }): Promise<void> {
    await this.rpc<null>('initiate_call_tx', {
      p_call_id: input.callId,
      p_conversation_id: input.conversationId,
      p_caller_id: input.callerId,
      p_callee_id: input.calleeId,
      p_call_type: input.callType,
      p_now_ms: input.nowMs,
    });
  }

  async isCallParticipant(callId: string, userId: string): Promise<boolean> {
    const rows = await this.requestJson<Array<{ call_id: string }>>(
      `/call_participants?select=call_id&call_id=eq.${this.enc(callId)}&user_id=eq.${this.enc(userId)}&limit=1`,
      { method: 'GET', headers: this.headers() }
    );
    return Boolean(rows[0]);
  }

  async findCallById(
    callId: string
  ): Promise<{
    id: string;
    status: string;
    started_at_ms: number;
    answered_at_ms: number | null;
    ended_at_ms: number | null;
  } | null> {
    const rows = await this.requestJson<
      Array<{
        id: string;
        status: string;
        started_at_ms: number;
        answered_at_ms: number | null;
        ended_at_ms: number | null;
      }>
    >(
      `/calls?select=id,status,started_at_ms,answered_at_ms,ended_at_ms&id=eq.${this.enc(callId)}&limit=1`,
      { method: 'GET', headers: this.headers() }
    );
    return rows[0] ?? null;
  }

  async acceptCallTx(callId: string, userId: string, nowMs: number): Promise<void> {
    const call = await this.findCallById(callId);
    await this.requestJson<unknown>(`/calls?id=eq.${this.enc(callId)}`, {
      method: 'PATCH',
      headers: this.headers({ Prefer: 'return=minimal' }),
      body: JSON.stringify({
        status: 'accepted',
        answered_at_ms: call?.answered_at_ms ?? nowMs,
      }),
    });
    await this.requestJson<unknown>(
      `/call_participants?call_id=eq.${this.enc(callId)}&user_id=eq.${this.enc(userId)}`,
      {
        method: 'PATCH',
        headers: this.headers({ Prefer: 'return=minimal' }),
        body: JSON.stringify({ state: 'joined', joined_at_ms: nowMs }),
      }
    );
  }

  async finishCallTx(
    callId: string,
    userId: string,
    nextCallStatus: string,
    participantState: string,
    endedAtMs: number,
    durationSeconds: number
  ): Promise<void> {
    await this.requestJson<unknown>(`/calls?id=eq.${this.enc(callId)}`, {
      method: 'PATCH',
      headers: this.headers({ Prefer: 'return=minimal' }),
      body: JSON.stringify({
        status: nextCallStatus,
        ended_at_ms: endedAtMs,
        duration_seconds: durationSeconds,
      }),
    });
    await this.requestJson<unknown>(
      `/call_participants?call_id=eq.${this.enc(callId)}&user_id=eq.${this.enc(userId)}`,
      {
        method: 'PATCH',
        headers: this.headers({ Prefer: 'return=minimal' }),
        body: JSON.stringify({ state: participantState, left_at_ms: endedAtMs }),
      }
    );
  }

  async listCallParticipantIds(callId: string): Promise<string[]> {
    const rows = await this.requestJson<Array<{ user_id: string }>>(
      `/call_participants?select=user_id&call_id=eq.${this.enc(callId)}`,
      { method: 'GET', headers: this.headers() }
    );
    return rows.map((r) => r.user_id);
  }

  async listUserCalls(userId: string, filter: string, limit: number): Promise<CallHistoryQueryRow[]> {
    return this.rpc<CallHistoryQueryRow[]>('list_user_calls', {
      p_user_id: userId,
      p_filter: filter,
      p_limit: limit,
    });
  }

  async listBlockedUsers(userId: string): Promise<BlockedUserRow[]> {
    return this.rpc<BlockedUserRow[]>('list_blocked_users_for_user', { p_user_id: userId });
  }

  async blockUser(blockerId: string, targetUserId: string, nowMs: number): Promise<void> {
    await this.requestJson<unknown>('/blocked_users?on_conflict=blocker_id,blocked_id', {
      method: 'POST',
      headers: this.headers({ Prefer: 'resolution=ignore-duplicates,return=minimal' }),
      body: JSON.stringify({
        blocker_id: blockerId,
        blocked_id: targetUserId,
        created_at_ms: nowMs,
      }),
    });
  }

  async unblockUser(blockerId: string, targetUserId: string): Promise<void> {
    await this.requestJson<unknown>(
      `/blocked_users?blocker_id=eq.${this.enc(blockerId)}&blocked_id=eq.${this.enc(targetUserId)}`,
      {
        method: 'DELETE',
        headers: this.headers({ Prefer: 'return=minimal' }),
      }
    );
  }

  async insertReport(input: {
    id: string;
    reporterId: string;
    targetUserId: string;
    messageId: string | null;
    reason: string;
    details: string | null;
    createdAtMs: number;
  }): Promise<void> {
    await this.requestJson<unknown>('/reports', {
      method: 'POST',
      headers: this.headers({ Prefer: 'return=minimal' }),
      body: JSON.stringify({
        id: input.id,
        reporter_id: input.reporterId,
        target_user_id: input.targetUserId,
        message_id: input.messageId,
        reason: input.reason,
        details: input.details,
        status: 'open',
        created_at_ms: input.createdAtMs,
      }),
    });
  }

  async upsertNotificationEndpoint(input: {
    id: string;
    userId: string;
    deviceId: string;
    transport: string;
    endpointUrl: string | null;
    updatedAtMs: number;
  }): Promise<void> {
    await this.requestJson<unknown>('/notification_endpoints?on_conflict=user_id,device_id', {
      method: 'POST',
      headers: this.headers({ Prefer: 'resolution=merge-duplicates,return=minimal' }),
      body: JSON.stringify({
        id: input.id,
        user_id: input.userId,
        device_id: input.deviceId,
        transport: input.transport,
        endpoint_url: input.endpointUrl,
        updated_at_ms: input.updatedAtMs,
      }),
    });
  }

  async listNotificationEndpointsForUser(userId: string): Promise<NotificationEndpointRow[]> {
    return this.requestJson<NotificationEndpointRow[]>(
      `/notification_endpoints?select=id,user_id,device_id,transport,endpoint_url,updated_at_ms&user_id=eq.${this.enc(userId)}`,
      { method: 'GET', headers: this.headers() }
    );
  }

  async deleteNotificationEndpoint(userId: string, deviceId: string): Promise<void> {
    await this.requestJson<unknown>(
      `/notification_endpoints?user_id=eq.${this.enc(userId)}&device_id=eq.${this.enc(deviceId)}`,
      {
        method: 'DELETE',
        headers: this.headers({ Prefer: 'return=minimal' }),
      }
    );
  }

  async updateUserPresence(userId: string, isOnline: boolean, lastSeenMs: number): Promise<void> {
    await this.requestJson<unknown>(`/user_profiles?user_id=eq.${this.enc(userId)}`, {
      method: 'PATCH',
      headers: this.headers({ Prefer: 'return=minimal' }),
      body: JSON.stringify({
        is_online: isOnline,
        last_seen_ms: lastSeenMs,
      }),
    });
  }

  async listPresencePeerIds(userId: string): Promise<string[]> {
    const convRows = await this.requestJson<Array<{ conversation_id: string }>>(
      `/conversation_members?select=conversation_id&user_id=eq.${this.enc(userId)}`,
      { method: 'GET', headers: this.headers() }
    );
    const convIds = Array.from(new Set(convRows.map((r) => r.conversation_id))).slice(0, 100);
    if (convIds.length === 0) return [];

    const inList = convIds.map((id) => encodeURIComponent(id)).join(',');
    const [memberRows, blockedRows] = await Promise.all([
      this.requestJson<Array<{ user_id: string }>>(
        `/conversation_members?select=user_id&conversation_id=in.(${inList})&user_id=neq.${this.enc(userId)}`,
        { method: 'GET', headers: this.headers() }
      ),
      this.requestJson<Array<{ blocker_id: string; blocked_id: string }>>(
        `/blocked_users?select=blocker_id,blocked_id&or=(blocker_id.eq.${this.enc(userId)},blocked_id.eq.${this.enc(userId)})`,
        { method: 'GET', headers: this.headers() }
      ),
    ]);

    const blockedPeerIds = new Set<string>();
    for (const row of blockedRows) {
      if (row.blocker_id !== userId) blockedPeerIds.add(row.blocker_id);
      if (row.blocked_id !== userId) blockedPeerIds.add(row.blocked_id);
    }

    const peerIds = new Set<string>();
    for (const row of memberRows) {
      if (row.user_id !== userId && !blockedPeerIds.has(row.user_id)) {
        peerIds.add(row.user_id);
      }
    }
    return Array.from(peerIds);
  }

  async checkConversationPair(
    conversationId: string,
    userId: string,
    targetUserId: string
  ): Promise<boolean> {
    const result = await this.rpc<boolean>('check_conversation_pair', {
      p_conversation_id: conversationId,
      p_user_id: userId,
      p_target_user_id: targetUserId,
    });
    return Boolean(result);
  }

  async getOwnerActivitySnapshot(input: {
    search: string | null;
    limit: number;
    offset: number;
    last24hCutoff: number;
    nowMs: number;
  }): Promise<OwnerSnapshotDbResult> {
    return this.rpc<OwnerSnapshotDbResult>('get_owner_activity_snapshot', {
      p_search: input.search,
      p_limit: input.limit,
      p_offset: input.offset,
      p_last_24h_cutoff: input.last24hCutoff,
      p_now_ms: input.nowMs,
    });
  }

  async insertAuditLog(input: {
    id: string;
    userId: string | null;
    eventType: string;
    outcome: string;
    ipHash: string | null;
    metaJson: Record<string, string | number | boolean> | null;
    createdAtMs: number;
  }): Promise<void> {
    await this.requestJson<unknown>('/audit_logs', {
      method: 'POST',
      headers: this.headers({ Prefer: 'return=minimal' }),
      body: JSON.stringify({
        id: input.id,
        user_id: input.userId,
        event_type: input.eventType,
        outcome: input.outcome,
        ip_hash: input.ipHash,
        meta_json: input.metaJson,
        created_at_ms: input.createdAtMs,
      }),
    });
  }
}

export function createSupabaseDb(env: Env): SupabaseDatabase {
  return new SupabaseDatabase(env.SUPABASE_URL, env.SUPABASE_SERVICE_ROLE_KEY);
}
