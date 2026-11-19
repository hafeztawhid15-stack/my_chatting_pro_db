package com.mychattingpro.domain.models

enum class MessageType {
    TEXT, IMAGE, VIDEO, VOICE, AUDIO, FILE, CALL_LOG;

    companion object {
        fun fromApi(raw: String): MessageType = when (raw.lowercase()) {
            "image" -> IMAGE
            "video" -> VIDEO
            "voice" -> VOICE
            "audio" -> AUDIO
            "file" -> FILE
            "call_log" -> CALL_LOG
            else -> TEXT
        }
    }
}

enum class MessageStatus {
    SENDING, SENT, DELIVERED, READ, FAILED;

    companion object {
        fun fromApi(raw: String?): MessageStatus = when (raw?.uppercase()) {
            "SENDING" -> SENDING
            "DELIVERED" -> DELIVERED
            "READ" -> READ
            "FAILED" -> FAILED
            else -> SENT
        }
    }
}

data class UserProfile(
    val id: String,
    val phoneE164: String,
    val displayName: String,
    val about: String,
    val avatarMediaId: String?,
    val isOnline: Boolean = false,
    val lastSeenMs: Long? = null
)

data class PublicUser(
    val id: String,
    val displayName: String,
    val about: String,
    val avatarMediaId: String?,
    val isOnline: Boolean,
    val lastSeenMs: Long?
)

data class Conversation(
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
    val lastMessageType: MessageType?,
    val lastMessageSenderId: String?,
    val lastMessageAtMs: Long?,
    val unreadCount: Int,
    val updatedAtMs: Long
)

data class MediaAttachment(
    val id: String,
    val category: String,
    val originalFilename: String,
    val mimeType: String,
    val sizeBytes: Long,
    val durationMs: Long? = null
)

data class Message(
    val id: String,
    val conversationId: String,
    val senderId: String,
    val clientMessageId: String,
    val type: MessageType,
    val bodyText: String?,
    val media: MediaAttachment?,
    val status: MessageStatus,
    val localUri: String? = null,
    val uploadProgress: Float? = null,
    val deletedForAllAtMs: Long? = null,
    val createdAtMs: Long,
    val deliveredAtMs: Long? = null,
    val readAtMs: Long? = null
)

data class BlockedUser(
    val userId: String,
    val displayName: String,
    val avatarMediaId: String?,
    val blockedAtMs: Long
)

data class IceServerConfig(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null
)

data class ActiveDeviceSession(
    val sessionId: String,
    val deviceId: String,
    val deviceName: String,
    val platform: String,
    val appVersion: String,
    val lastUsedAtMs: Long,
    val createdAtMs: Long,
    val isCurrent: Boolean
)

data class OwnerActivitySummary(
    val totalRegisteredUsers: Int,
    val currentlyOnlineUsers: Int,
    val activeLast24hUsers: Int,
    val activeSessionsCount: Int,
    val generatedAtMs: Long
)

data class OwnerTrackedUser(
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

data class OwnerActivitySnapshot(
    val summary: OwnerActivitySummary,
    val users: List<OwnerTrackedUser>,
    val page: Int = 1,
    val totalCount: Int = users.size,
    val hasMore: Boolean = false
)
