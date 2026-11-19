package com.mychattingpro.core.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "cached_conversations")
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
    val updatedAtMs: Long
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
    val localStatus: String, // SENDING, SENT, DELIVERED, READ, FAILED
    val localUri: String?,
    val uploadProgress: Float?,
    val deletedForAllAtMs: Long?,
    val createdAtMs: Long,
    val deliveredAtMs: Long?,
    val readAtMs: Long?
)
