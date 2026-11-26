package com.mychattingpro.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Query("SELECT * FROM cached_conversations WHERE isPrivate = 0 ORDER BY isPinned DESC, COALESCE(pinnedAtMs, 0) DESC, COALESCE(lastMessageAtMs, updatedAtMs) DESC")
    fun observeConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM cached_conversations WHERE isPrivate = 1 ORDER BY isPinned DESC, COALESCE(pinnedAtMs, 0) DESC, COALESCE(lastMessageAtMs, updatedAtMs) DESC")
    fun observePrivateConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM cached_conversations WHERE id = :id LIMIT 1")
    fun observeConversation(id: String): Flow<ConversationEntity?>

    @Query("SELECT * FROM cached_conversations WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<ConversationEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ConversationEntity)

    @Query("UPDATE cached_conversations SET peerIsOnline = :isOnline, peerLastSeenMs = :lastSeenMs WHERE peerUserId = :peerUserId")
    suspend fun updatePeerPresence(peerUserId: String, isOnline: Boolean, lastSeenMs: Long?)

    @Query("UPDATE cached_conversations SET unreadCount = 0, markedUnread = 0 WHERE id = :conversationId")
    suspend fun clearUnreadCount(conversationId: String)

    @Query("UPDATE cached_conversations SET markedUnread = :markedUnread WHERE id = :conversationId")
    suspend fun updateMarkedUnread(conversationId: String, markedUnread: Boolean)

    @Query("UPDATE cached_conversations SET isPinned = :isPinned, pinnedAtMs = :pinnedAtMs WHERE id = :conversationId")
    suspend fun updatePinned(conversationId: String, isPinned: Boolean, pinnedAtMs: Long?)

    @Query("UPDATE cached_conversations SET isArchived = :isArchived, archivedAtMs = :archivedAtMs WHERE id = :conversationId")
    suspend fun updateArchived(conversationId: String, isArchived: Boolean, archivedAtMs: Long?)

    @Query("UPDATE cached_conversations SET mutedUntilMs = :mutedUntilMs WHERE id = :conversationId")
    suspend fun updateMutedUntil(conversationId: String, mutedUntilMs: Long?)

    @Query("UPDATE cached_conversations SET customSoundUri = :soundUri, customSoundLabel = :soundLabel WHERE id = :conversationId")
    suspend fun updateCustomSound(conversationId: String, soundUri: String?, soundLabel: String?)

    @Query("UPDATE cached_conversations SET lastMessageId = NULL, lastMessagePreview = NULL, lastMessageType = NULL, lastMessageSenderId = NULL, clearedAtMs = :clearedAtMs, unreadCount = 0, markedUnread = 0 WHERE id = :conversationId")
    suspend fun clearLastMessageSummary(conversationId: String, clearedAtMs: Long)

    @Query("UPDATE cached_conversations SET lastMessageId = :lastMessageId, lastMessagePreview = :lastMessagePreview, lastMessageType = :lastMessageType, lastMessageSenderId = :lastMessageSenderId, lastMessageAtMs = :lastMessageAtMs WHERE id = :conversationId")
    suspend fun updateLastMessageSummary(
        conversationId: String,
        lastMessageId: String?,
        lastMessagePreview: String?,
        lastMessageType: String?,
        lastMessageSenderId: String?,
        lastMessageAtMs: Long?
    )

    @Query("UPDATE cached_conversations SET isBlockedByMe = :blocked WHERE peerUserId = :peerUserId")
    suspend fun updateBlockedByMe(peerUserId: String, blocked: Boolean)

    @Query("UPDATE cached_conversations SET hasBlockedMe = :blocked, peerIsOnline = CASE WHEN :blocked = 1 THEN 0 ELSE peerIsOnline END, peerLastSeenMs = CASE WHEN :blocked = 1 THEN NULL ELSE peerLastSeenMs END WHERE peerUserId = :peerUserId")
    suspend fun updateHasBlockedMe(peerUserId: String, blocked: Boolean)

    @Query("UPDATE cached_conversations SET isPrivate = :isPrivate WHERE id = :conversationId")
    suspend fun updatePrivateState(conversationId: String, isPrivate: Boolean)

    @Query("DELETE FROM cached_conversations WHERE isPrivate = :isPrivate AND id NOT IN (:activeIds)")
    suspend fun deleteMissingByPrivacy(isPrivate: Boolean, activeIds: List<String>)

    @Query("DELETE FROM cached_conversations WHERE isPrivate = :isPrivate")
    suspend fun deleteAllByPrivacy(isPrivate: Boolean)

    @Query("DELETE FROM cached_conversations WHERE peerUserId = :peerUserId AND isPrivate = 0")
    suspend fun deleteRegularCopiesForPeer(peerUserId: String)

    @Query("DELETE FROM cached_conversations WHERE id = :conversationId")
    suspend fun deleteById(conversationId: String)

    @Query("DELETE FROM cached_conversations WHERE isPrivate = 1")
    suspend fun clearPrivate()

    @Query("DELETE FROM cached_conversations")
    suspend fun clearAll()
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM cached_messages WHERE conversationId = :conversationId AND isPrivate = 0 AND id NOT IN (SELECT messageId FROM hidden_messages) ORDER BY createdAtMs ASC, id ASC")
    fun observeMessages(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM cached_messages WHERE conversationId = :conversationId AND isPrivate = 1 AND id NOT IN (SELECT messageId FROM hidden_messages) ORDER BY createdAtMs ASC, id ASC")
    fun observePrivateMessages(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM cached_messages WHERE conversationId = :conversationId AND isPrivate = :isPrivate AND id NOT IN (SELECT messageId FROM hidden_messages) ORDER BY createdAtMs DESC, id DESC LIMIT 1")
    suspend fun getLatestVisibleMessage(conversationId: String, isPrivate: Boolean): MessageEntity?

    @Query("SELECT * FROM cached_messages WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): MessageEntity?

    @Query("SELECT * FROM cached_messages WHERE clientMessageId = :clientMessageId LIMIT 1")
    suspend fun getByClientId(clientMessageId: String): MessageEntity?

    @Query("SELECT * FROM cached_messages WHERE localStatus IN ('FAILED', 'SENDING') ORDER BY createdAtMs ASC")
    suspend fun getPendingOrFailedMessages(): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<MessageEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: MessageEntity)

    @Query("DELETE FROM cached_messages WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM cached_messages WHERE conversationId = :conversationId AND isPrivate = :isPrivate")
    suspend fun clearConversationMessages(conversationId: String, isPrivate: Boolean)

    @Query("UPDATE cached_messages SET deliveredAtMs = COALESCE(deliveredAtMs, :atMs), localStatus = CASE WHEN localStatus = 'READ' THEN 'READ' ELSE 'DELIVERED' END WHERE id IN (:messageIds)")
    suspend fun markDelivered(messageIds: List<String>, atMs: Long)

    @Query("UPDATE cached_messages SET deliveredAtMs = COALESCE(deliveredAtMs, :atMs), readAtMs = COALESCE(readAtMs, :atMs), localStatus = 'READ' WHERE id IN (:messageIds)")
    suspend fun markRead(messageIds: List<String>, atMs: Long)

    @Query("UPDATE cached_messages SET deletedForAllAtMs = :deletedAtMs, bodyText = NULL, mediaId = NULL WHERE id = :messageId")
    suspend fun markDeletedForAll(messageId: String, deletedAtMs: Long)

    @Query("UPDATE cached_messages SET isPrivate = :isPrivate WHERE conversationId = :conversationId")
    suspend fun updateConversationMessagesPrivateState(conversationId: String, isPrivate: Boolean)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun hideMessage(item: HiddenMessageEntity)

    @Query("DELETE FROM hidden_messages")
    suspend fun clearHiddenMessages()

    @Query("DELETE FROM cached_messages WHERE isPrivate = 1")
    suspend fun clearPrivate()

    @Query("DELETE FROM cached_messages")
    suspend fun clearAll()
}

@Dao
interface CallHistoryDao {
    @Query("SELECT * FROM cached_call_history WHERE callId NOT IN (SELECT callId FROM hidden_calls) ORDER BY startedAtMs DESC")
    fun observeAll(): Flow<List<CallHistoryEntity>>

    @Query("SELECT * FROM cached_call_history WHERE (conversationId = :conversationId OR peerUserId = :peerUserId) AND callId NOT IN (SELECT callId FROM hidden_calls) ORDER BY startedAtMs ASC")
    fun observeCallsForPeer(conversationId: String, peerUserId: String): Flow<List<CallHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<CallHistoryEntity>)

    @Query("DELETE FROM cached_call_history WHERE callId = :callId")
    suspend fun deleteById(callId: String)

    @Query("DELETE FROM cached_call_history WHERE callId IN (:callIds)")
    suspend fun deleteByIds(callIds: List<String>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun hideCall(item: HiddenCallEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun hideCalls(items: List<HiddenCallEntity>)

    @Query("DELETE FROM hidden_calls")
    suspend fun clearHiddenCalls()

    @Query("DELETE FROM cached_call_history")
    suspend fun clearAll()

    @Transaction
    suspend fun replaceAll(items: List<CallHistoryEntity>) {
        clearAll()
        if (items.isNotEmpty()) {
            upsertAll(items)
        }
    }
}

@Dao
interface SecurityAlertDao {
    @Query("SELECT * FROM security_alerts ORDER BY createdAtMs DESC")
    fun observeAlerts(): Flow<List<SecurityAlertEntity>>

    @Query("SELECT COUNT(*) FROM security_alerts WHERE isRead = 0")
    fun observeUnreadCount(): Flow<Int>

    @Query("SELECT * FROM security_alerts")
    suspend fun getAll(): List<SecurityAlertEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: SecurityAlertEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<SecurityAlertEntity>)

    @Query("UPDATE security_alerts SET isRead = 1 WHERE isRead = 0")
    suspend fun markAllRead()

    @Query("UPDATE security_alerts SET isRead = 1 WHERE id = :id")
    suspend fun markRead(id: String)

    @Query("SELECT * FROM security_alerts WHERE sessionId = :sessionId")
    suspend fun getBySessionId(sessionId: String): List<SecurityAlertEntity>

    @Query("DELETE FROM security_alerts WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM security_alerts WHERE sessionId = :sessionId")
    suspend fun deleteBySessionId(sessionId: String)

    @Query("DELETE FROM security_alerts WHERE alertType IN (:types)")
    suspend fun deleteByTypes(types: List<String>)

    @Query("DELETE FROM security_alerts")
    suspend fun clearAll()
}
