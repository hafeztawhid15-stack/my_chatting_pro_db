package com.mychattingpro.core.network

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class RegisterRequestDto(
    val phone: String,
    val countryIso2: String,
    val callingCode: String,
    val pin: String,
    val confirmPin: String,
    val displayName: String,
    val deviceName: String,
    val appVersion: String
)

@JsonClass(generateAdapter = true)
data class CheckPhoneRequestDto(
    val phone: String
)

@JsonClass(generateAdapter = true)
data class CheckPhoneResponseDto(
    val available: Boolean,
    val phoneE164: String? = null
)

@JsonClass(generateAdapter = true)
data class LoginRequestDto(
    val phone: String,
    val pin: String,
    val deviceName: String,
    val appVersion: String
)

@JsonClass(generateAdapter = true)
data class RefreshRequestDto(
    val refreshToken: String
)

@JsonClass(generateAdapter = true)
data class AuthUserDto(
    val id: String,
    val phoneE164: String,
    val displayName: String,
    val about: String,
    val avatarMediaId: String?,
    val phoneVerified: Boolean = false
)

@JsonClass(generateAdapter = true)
data class AuthResponseDto(
    val accessToken: String,
    val refreshToken: String,
    val expiresInSeconds: Int,
    val user: AuthUserDto
)

@JsonClass(generateAdapter = true)
data class RefreshResponseDto(
    val accessToken: String,
    val refreshToken: String,
    val expiresInSeconds: Int
)

@JsonClass(generateAdapter = true)
data class ProfileDto(
    val id: String,
    val phoneE164: String,
    val displayName: String,
    val about: String,
    val avatarMediaId: String?,
    val isOnline: Boolean = false,
    val lastSeenMs: Long? = null
)

@JsonClass(generateAdapter = true)
data class UpdateProfileRequestDto(
    val displayName: String?,
    val about: String?,
    val avatarMediaId: String? = null
)

@JsonClass(generateAdapter = true)
data class DeleteAccountRequestDto(
    val pin: String,
    val confirmText: String = "DELETE"
)

@JsonClass(generateAdapter = true)
data class DeleteAccountResponseDto(
    val deleted: Boolean,
    val deletedAtMs: Long,
    val removedMediaObjects: Int,
    val retentionNotice: String
)

@JsonClass(generateAdapter = true)
data class ChangeLoginPinRequestDto(
    val currentPin: String,
    val newPin: String,
    val confirmNewPin: String
)

@JsonClass(generateAdapter = true)
data class ChangeLoginPinResponseDto(
    val updatedAtMs: Long,
    val revokedOtherSessions: Boolean = false
)

@JsonClass(generateAdapter = true)
data class GenerateRecoveryCodeRequestDto(
    val currentPin: String
)

@JsonClass(generateAdapter = true)
data class GenerateRecoveryCodeResponseDto(
    val recoveryCode: String,
    val generatedAtMs: Long
)

@JsonClass(generateAdapter = true)
data class ResetLoginPinRequestDto(
    val phone: String,
    val recoveryCode: String,
    val newPin: String,
    val confirmNewPin: String,
    val deviceName: String,
    val appVersion: String
)

@JsonClass(generateAdapter = true)
data class LogoutAllRequestDto(
    val keepCurrent: Boolean = false
)

@JsonClass(generateAdapter = true)
data class LogoutAllResponseDto(
    val revokedAll: Boolean = true
)

@JsonClass(generateAdapter = true)
data class PublicUserDto(
    val id: String,
    val displayName: String,
    val about: String,
    val avatarMediaId: String?,
    val isOnline: Boolean,
    val lastSeenMs: Long?
)

@JsonClass(generateAdapter = true)
data class UserSearchResponseDto(
    val found: Boolean,
    val user: PublicUserDto?,
    val message: String? = null
)

@JsonClass(generateAdapter = true)
data class ConversationDto(
    val id: String,
    val type: String,
    val peerUserId: String,
    val peerDisplayName: String,
    val peerAvatarMediaId: String?,
    val peerAbout: String,
    val peerIsOnline: Boolean,
    val peerLastSeenMs: Long?,
    val isBlockedByMe: Boolean,
    val hasBlockedMe: Boolean,
    val lastMessageId: String?,
    val lastMessagePreview: String?,
    val lastMessageType: String?,
    val lastMessageSenderId: String?,
    val lastMessageAtMs: Long?,
    val unreadCount: Int,
    val updatedAtMs: Long,
    val isPrivate: Boolean = false,
    val isPinned: Boolean = false,
    val pinnedAtMs: Long? = null,
    val isArchived: Boolean = false,
    val archivedAtMs: Long? = null,
    val mutedUntilMs: Long? = null,
    val customSoundUri: String? = null,
    val customSoundLabel: String? = null,
    val markedUnread: Boolean = false
)

@JsonClass(generateAdapter = true)
data class UpdateConversationPreferencesRequestDto(
    val isPinned: Boolean? = null,
    val isArchived: Boolean? = null,
    val mutedUntilMs: Long? = null,
    val customSoundUri: String? = null,
    val customSoundLabel: String? = null,
    val markedUnread: Boolean? = null
)

@JsonClass(generateAdapter = true)
data class ClearConversationResponseDto(
    val clearedAtMs: Long
)

@JsonClass(generateAdapter = true)
data class DeleteConversationResponseDto(
    val deletedAtMs: Long
)

@JsonClass(generateAdapter = true)
data class PeerProfileResponseDto(
    val user: PublicUserDto,
    val isBlockedByMe: Boolean,
    val hasBlockedMe: Boolean
)

@JsonClass(generateAdapter = true)
data class ConversationsListResponseDto(
    val conversations: List<ConversationDto>
)

@JsonClass(generateAdapter = true)
data class DirectConversationRequestDto(
    val targetUserId: String
)

@JsonClass(generateAdapter = true)
data class SingleConversationResponseDto(
    val conversation: ConversationDto
)

@JsonClass(generateAdapter = true)
data class MediaObjectDto(
    val id: String,
    val category: String,
    val originalFilename: String,
    val mimeType: String,
    val sizeBytes: Long,
    val durationMs: Long? = null,
    val width: Int? = null,
    val height: Int? = null
)

@JsonClass(generateAdapter = true)
data class MessageDto(
    val id: String,
    val conversationId: String,
    val senderId: String,
    val clientMessageId: String,
    val messageType: String,
    val bodyText: String?,
    val media: MediaObjectDto?,
    val replyToMessageId: String? = null,
    val deletedForAllAtMs: Long? = null,
    val createdAtMs: Long,
    val deliveredAtMs: Long? = null,
    val readAtMs: Long? = null,
    val isPrivate: Boolean = false
)

@JsonClass(generateAdapter = true)
data class MessagesPageResponseDto(
    val messages: List<MessageDto>,
    val hasMore: Boolean,
    val nextCursorTs: Long?,
    val nextCursorId: String?
)

@JsonClass(generateAdapter = true)
data class SendMessageRequestDto(
    val conversationId: String,
    val clientMessageId: String,
    val messageType: String,
    val bodyText: String? = null,
    val mediaId: String? = null,
    val replyToMessageId: String? = null
)

@JsonClass(generateAdapter = true)
data class SendMessageResponseDto(
    val message: MessageDto
)

@JsonClass(generateAdapter = true)
data class ReceiptsRequestDto(
    val conversationId: String,
    val messageIds: List<String>,
    val type: String
)

@JsonClass(generateAdapter = true)
data class MediaUploadResponseDto(
    val media: MediaObjectDto
)

@JsonClass(generateAdapter = true)
data class SignedMediaUrlResponseDto(
    val mediaId: String,
    val signedToken: String,
    val expiresAtMs: Long,
    val mimeType: String,
    val originalFilename: String,
    val sizeBytes: Long,
    val downloadUrl: String
)

@JsonClass(generateAdapter = true)
data class IceServerDto(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null
)

@JsonClass(generateAdapter = true)
data class IceServersResponseDto(
    val iceServers: List<IceServerDto>,
    val turnConfigured: Boolean
)

@JsonClass(generateAdapter = true)
data class CreateCallRequestDto(
    val conversationId: String,
    val calleeId: String,
    val callType: String
)

@JsonClass(generateAdapter = true)
data class CallRecordDto(
    val callId: String,
    val conversationId: String,
    val callType: String,
    val status: String,
    val startedAtMs: Long
)

@JsonClass(generateAdapter = true)
data class CreateCallResponseDto(
    val call: CallRecordDto
)

@JsonClass(generateAdapter = true)
data class UpdateCallStateRequestDto(
    val action: String
)

@JsonClass(generateAdapter = true)
data class CallHistoryItemDto(
    val callId: String,
    val conversationId: String,
    val callerId: String,
    val peerUserId: String,
    val peerDisplayName: String,
    val peerAvatarMediaId: String?,
    val callType: String,
    val status: String,
    val direction: String,
    val startedAtMs: Long,
    val answeredAtMs: Long?,
    val endedAtMs: Long?,
    val durationSeconds: Int
)

@JsonClass(generateAdapter = true)
data class CallHistoryResponseDto(
    val calls: List<CallHistoryItemDto>
)

@JsonClass(generateAdapter = true)
data class DeleteCallHistoryBatchRequestDto(
    val callIds: List<String>
)

@JsonClass(generateAdapter = true)
data class CallStatusResponseDto(
    val callId: String,
    val status: String,
    val answeredAtMs: Long? = null,
    val endedAtMs: Long? = null
)

@JsonClass(generateAdapter = true)
data class NotificationEndpointRequestDto(
    val transport: String,
    val endpointUrl: String? = null
)

@JsonClass(generateAdapter = true)
data class BlockUserRequestDto(
    val targetUserId: String
)

@JsonClass(generateAdapter = true)
data class BlockedUserDto(
    val userId: String,
    val displayName: String,
    val avatarMediaId: String?,
    val blockedAtMs: Long
)

@JsonClass(generateAdapter = true)
data class BlockedUsersResponseDto(
    val blockedUsers: List<BlockedUserDto>
)

@JsonClass(generateAdapter = true)
data class ReportRequestDto(
    val targetUserId: String,
    val messageId: String? = null,
    val reason: String,
    val details: String? = null
)

@JsonClass(generateAdapter = true)
data class ApiErrorResponseDto(
    val error: String? = null,
    val message: String? = null,
    val retryAfterSeconds: Int? = null
)

@JsonClass(generateAdapter = true)
data class ActiveSessionDto(
    val sessionId: String,
    val deviceId: String,
    val deviceName: String,
    val platform: String,
    val appVersion: String,
    val lastUsedAtMs: Long,
    val createdAtMs: Long,
    val isCurrent: Boolean
)

@JsonClass(generateAdapter = true)
data class ActiveSessionsResponseDto(
    val sessions: List<ActiveSessionDto>
)

@JsonClass(generateAdapter = true)
data class SecurityAlertDto(
    val id: String,
    val alertType: String,
    val deviceName: String? = null,
    val sessionId: String? = null,
    val createdAtMs: Long,
    val isCurrentSession: Boolean = false,
    val isSessionActive: Boolean = false
)

@JsonClass(generateAdapter = true)
data class SecurityAlertsResponseDto(
    val alerts: List<SecurityAlertDto>
)

@JsonClass(generateAdapter = true)
data class OwnerActivitySummaryDto(
    val totalRegisteredUsers: Int,
    val currentlyOnlineUsers: Int,
    val activeLast24hUsers: Int,
    val activeSessionsCount: Int,
    val generatedAtMs: Long
)

@JsonClass(generateAdapter = true)
data class OwnerTrackedUserDto(
    val userId: String,
    val displayName: String,
    val countryIso2: String,
    val maskedPhone: String,
    val isOnline: Boolean,
    val lastSeenMs: Long?,
    val createdAtMs: Long,
    val latestDeviceName: String,
    val latestPlatform: String,
    val latestAppVersion: String,
    val activeSessionCount: Int
)

@JsonClass(generateAdapter = true)
data class OwnerPaginationDto(
    val page: Int = 1,
    val limit: Int = 20,
    val totalCount: Int = 0,
    val hasMore: Boolean = false
)

@JsonClass(generateAdapter = true)
data class OwnerActivityResponseDto(
    val summary: OwnerActivitySummaryDto,
    val pagination: OwnerPaginationDto? = null,
    val users: List<OwnerTrackedUserDto>
)

@JsonClass(generateAdapter = true)
data class PrivatePinStatusResponseDto(
    val hasPrivatePin: Boolean,
    val lockedUntilMs: Long? = null,
    val retryAfterSeconds: Int = 0
)

@JsonClass(generateAdapter = true)
data class PrivatePinSetupRequestDto(
    val pin: String,
    val confirmPin: String
)

@JsonClass(generateAdapter = true)
data class PrivatePinUnlockRequestDto(
    val pin: String
)

@JsonClass(generateAdapter = true)
data class PrivatePinChangeRequestDto(
    val oldPin: String,
    val newPin: String,
    val confirmNewPin: String
)

@JsonClass(generateAdapter = true)
data class PrivatePinResetRequestDto(
    val loginPin: String,
    val newPrivatePin: String,
    val confirmNewPrivatePin: String
)

@JsonClass(generateAdapter = true)
data class PrivatePinUnlockResponseDto(
    val unlocked: Boolean,
    val privateUnlockToken: String,
    val expiresInSeconds: Int
)
