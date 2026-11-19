package com.mychattingpro.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Query("SELECT * FROM cached_conversations ORDER BY COALESCE(lastMessageAtMs, updatedAtMs) DESC")
    fun observeConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM cached_conversations WHERE id = :id LIMIT 1")
    fun observeConversation(id: String): Flow<ConversationEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<ConversationEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ConversationEntity)

    @Query("UPDATE cached_conversations SET peerIsOnline = :isOnline, peerLastSeenMs = :lastSeenMs WHERE peerUserId = :peerUserId")
    suspend fun updatePeerPresence(peerUserId: String, isOnline: Boolean, lastSeenMs: Long?)

    @Query("UPDATE cached_conversations SET unreadCount = 0 WHERE id = :conversationId")
    suspend fun clearUnreadCount(conversationId: String)

    @Query("UPDATE cached_conversations SET isBlockedByMe = :blocked WHERE peerUserId = :peerUserId")
    suspend fun updateBlockedByMe(peerUserId: String, blocked: Boolean)

    @Query("DELETE FROM cached_conversations")
    suspend fun clearAll()
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM cached_messages WHERE conversationId = :conversationId ORDER BY createdAtMs ASC, id ASC")
    fun observeMessages(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM cached_messages WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): MessageEntity?

    @Query("SELECT * FROM cached_messages WHERE clientMessageId = :clientMessageId LIMIT 1")
    suspend fun getByClientId(clientMessageId: String): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<MessageEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: MessageEntity)

    @Query("DELETE FROM cached_messages WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE cached_messages SET deliveredAtMs = COALESCE(deliveredAtMs, :atMs), localStatus = CASE WHEN localStatus = 'READ' THEN 'READ' ELSE 'DELIVERED' END WHERE id IN (:messageIds)")
    suspend fun markDelivered(messageIds: List<String>, atMs: Long)

    @Query("UPDATE cached_messages SET deliveredAtMs = COALESCE(deliveredAtMs, :atMs), readAtMs = COALESCE(readAtMs, :atMs), localStatus = 'READ' WHERE id IN (:messageIds)")
    suspend fun markRead(messageIds: List<String>, atMs: Long)

    @Query("UPDATE cached_messages SET deletedForAllAtMs = :deletedAtMs, bodyText = NULL, mediaId = NULL WHERE id = :messageId")
    suspend fun markDeletedForAll(messageId: String, deletedAtMs: Long)

    @Query("DELETE FROM cached_messages")
    suspend fun clearAll()
}
