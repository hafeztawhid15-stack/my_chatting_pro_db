package com.mychattingpro.core.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "cached_conversations",
    indices = [
        Index(value = ["isPrivate", "isPinned", "updatedAtMs"]),
        Index(value = ["peerUserId"])
    ]
)
data class ConversationEntity(
    @PrimaryKey val id: String,
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
    val markedUnread: Boolean = false,
    val clearedAtMs: Long? = null
)

@Entity(
    tableName = "cached_messages",
    indices = [
        Index(value = ["conversationId", "createdAtMs"]),
        Index(value = ["clientMessageId"], unique = true)
    ]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val senderId: String,
    val clientMessageId: String,
    val messageType: String,
    val bodyText: String?,
    val mediaId: String?,
    val mediaCategory: String?,
    val mediaFilename: String?,
    val mediaMimeType: String?,
    val mediaSizeBytes: Long?,
    val mediaDurationMs: Long?,
    val mediaWidth: Int? = null,
    val mediaHeight: Int? = null,
    val localStatus: String, // SENDING, SENT, DELIVERED, READ, FAILED
    val localUri: String?,
    val uploadProgress: Float?,
    val deletedForAllAtMs: Long?,
    val createdAtMs: Long,
    val deliveredAtMs: Long?,
    val readAtMs: Long?,
    val isPrivate: Boolean = false,
    val replyToMessageId: String? = null
)

@Entity(
    tableName = "cached_call_history",
    indices = [Index(value = ["startedAtMs"])]
)
data class CallHistoryEntity(
    @PrimaryKey val callId: String,
    val conversationId: String,
    val callerId: String,
    val peerUserId: String,
    val peerDisplayName: String,
    val peerAvatarMediaId: String?,
    val callType: String, // audio, video
    val status: String, // ringing, accepted, rejected, missed, ended
    val direction: String, // incoming, outgoing
    val startedAtMs: Long,
    val answeredAtMs: Long?,
    val endedAtMs: Long?,
    val durationSeconds: Int
)
