package com.mychattingpro.domain.repository

import android.net.Uri
import com.mychattingpro.core.common.Country
import com.mychattingpro.domain.models.ActiveDeviceSession
import com.mychattingpro.domain.models.BlockedUser
import com.mychattingpro.domain.models.CallHistoryFilter
import com.mychattingpro.domain.models.CallHistoryItem
import com.mychattingpro.domain.models.Conversation
import com.mychattingpro.domain.models.ConversationPeerProfile
import com.mychattingpro.domain.models.IceServerConfig
import com.mychattingpro.domain.models.MediaAttachment
import com.mychattingpro.domain.models.Message
import com.mychattingpro.domain.models.MessageType
import com.mychattingpro.domain.models.OwnerActivitySnapshot
import com.mychattingpro.domain.models.PrivatePinStatus
import com.mychattingpro.domain.models.PublicUser
import com.mychattingpro.domain.models.UserProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface AuthRepository {
    suspend fun checkPhoneAvailable(phoneE164: String): Result<Boolean>

    suspend fun register(
        phoneE164: String,
        country: Country,
        pin: String,
        confirmPin: String,
        displayName: String
    ): Result<UserProfile>

    suspend fun login(phoneE164: String, pin: String): Result<UserProfile>
    suspend fun logout(): Result<Unit>
    suspend fun logoutAllDevices(keepCurrent: Boolean): Result<Unit>
    suspend fun changeLoginPin(currentPin: String, newPin: String, confirmNewPin: String): Result<Unit>
    suspend fun generateRecoveryCode(currentPin: String): Result<String>
    suspend fun resetLoginPinWithRecoveryCode(
        phoneE164: String,
        recoveryCode: String,
        newPin: String,
        confirmNewPin: String
    ): Result<UserProfile>
    suspend fun deleteAccount(pin: String, confirmText: String = "DELETE"): Result<String>
    suspend fun getActiveSessions(): Result<List<ActiveDeviceSession>>
    suspend fun revokeSession(sessionId: String): Result<Unit>
    suspend fun syncNotificationEndpoint(): Result<Unit>
}

interface OwnerRepository {
    suspend fun fetchOwnerActivity(
        ownerKey: String,
        query: String = "",
        page: Int = 1,
        limit: Int = 20
    ): Result<OwnerActivitySnapshot>
}

interface ProfileRepository {
    suspend fun fetchMyProfile(): Result<UserProfile>
    suspend fun updateProfile(displayName: String, about: String, avatarMediaId: String?): Result<UserProfile>
    suspend fun uploadProfilePhoto(uri: Uri): Result<MediaAttachment>
}

interface ChatRepository {
    fun observeConversations(): Flow<List<Conversation>>
    fun observeConversation(conversationId: String): Flow<Conversation?>
    suspend fun refreshConversations(): Result<Unit>
    suspend fun searchUser(phoneE164: String, nameFilter: String?): Result<PublicUser?>
    suspend fun startDirectChat(targetUserId: String): Result<Conversation>
    suspend fun setPinned(conversationId: String, pinned: Boolean): Result<Unit>
    suspend fun setArchived(conversationId: String, archived: Boolean): Result<Unit>
    suspend fun setMutedUntil(conversationId: String, mutedUntilMs: Long?): Result<Unit>
    suspend fun setCustomSound(conversationId: String, soundUri: String?, soundLabel: String?): Result<Unit>
    suspend fun setMarkedUnread(conversationId: String, unread: Boolean): Result<Unit>
    suspend fun clearConversation(conversationId: String): Result<Unit>
    suspend fun deleteConversation(conversationId: String): Result<Unit>
    suspend fun moveToPrivateChat(conversationId: String): Result<Unit>
    suspend fun getPeerProfile(conversationId: String): Result<ConversationPeerProfile>
}

interface MessageRepository {
    fun observeMessages(conversationId: String): Flow<List<Message>>
    suspend fun loadLatestMessages(conversationId: String): Result<Boolean>
    suspend fun loadOlderMessages(conversationId: String, beforeTs: Long, beforeId: String): Result<Boolean>
    suspend fun sendTextMessage(
        conversationId: String,
        text: String,
        clientMessageId: String? = null,
        replyToMessageId: String? = null
    ): Result<Message>
    suspend fun sendMediaMessage(
        conversationId: String,
        uri: Uri,
        messageType: MessageType,
        category: String,
        caption: String?,
        durationMs: Long? = null,
        existingClientMessageId: String? = null,
        replyToMessageId: String? = null,
        onProgress: (Float) -> Unit
    ): Result<Message>
    suspend fun retryFailedMessage(message: Message, onProgress: (Float) -> Unit): Result<Message>
    suspend fun retryAllPendingMessages(): Int
    suspend fun markReceipts(conversationId: String, messageIds: List<String>, read: Boolean): Result<Unit>
    suspend fun deleteOwnMessage(messageId: String): Result<Unit>
    suspend fun getSignedMediaUrl(mediaId: String): Result<String>
}

interface PrivateChatRepository {
    val isUnlocked: StateFlow<Boolean>
    fun lockPrivateChat()
    fun touchActivity()
    suspend fun getPinStatus(): Result<PrivatePinStatus>
    suspend fun setupPrivatePin(pin: String, confirmPin: String): Result<Unit>
    suspend fun unlockPrivateChat(pin: String): Result<Unit>
    suspend fun changePrivatePin(oldPin: String, newPin: String, confirmNewPin: String): Result<Unit>
    suspend fun resetPrivatePinWithAccountAuth(
        loginPin: String,
        newPrivatePin: String,
        confirmNewPrivatePin: String
    ): Result<Unit>
    fun observePrivateConversations(): Flow<List<Conversation>>
    fun observePrivateConversation(conversationId: String): Flow<Conversation?>
    suspend fun refreshPrivateConversations(): Result<Unit>
    suspend fun searchUserForPrivateChat(phoneE164: String, nameFilter: String?): Result<PublicUser?>
    suspend fun startPrivateDirectChat(targetUserId: String): Result<Conversation>
    suspend fun setPrivatePinned(conversationId: String, pinned: Boolean): Result<Unit>
    suspend fun setPrivateArchived(conversationId: String, archived: Boolean): Result<Unit>
    suspend fun setPrivateMutedUntil(conversationId: String, mutedUntilMs: Long?): Result<Unit>
    suspend fun setPrivateCustomSound(conversationId: String, soundUri: String?, soundLabel: String?): Result<Unit>
    suspend fun setPrivateMarkedUnread(conversationId: String, unread: Boolean): Result<Unit>
    suspend fun clearPrivateConversation(conversationId: String): Result<Unit>
    suspend fun deletePrivateConversation(conversationId: String): Result<Unit>
    suspend fun moveToRegularChat(conversationId: String): Result<Unit>
    suspend fun getPrivatePeerProfile(conversationId: String): Result<ConversationPeerProfile>
    fun observePrivateMessages(conversationId: String): Flow<List<Message>>
    suspend fun loadLatestPrivateMessages(conversationId: String): Result<Boolean>
    suspend fun loadOlderPrivateMessages(conversationId: String, beforeTs: Long, beforeId: String): Result<Boolean>
    suspend fun sendPrivateTextMessage(
        conversationId: String,
        text: String,
        clientMessageId: String? = null,
        replyToMessageId: String? = null
    ): Result<Message>
    suspend fun sendPrivateMediaMessage(
        conversationId: String,
        uri: Uri,
        messageType: MessageType,
        category: String,
        caption: String?,
        durationMs: Long? = null,
        existingClientMessageId: String? = null,
        replyToMessageId: String? = null,
        onProgress: (Float) -> Unit
    ): Result<Message>
    suspend fun retryFailedPrivateMessage(message: Message, onProgress: (Float) -> Unit): Result<Message>
    suspend fun markPrivateReceipts(conversationId: String, messageIds: List<String>, read: Boolean): Result<Unit>
    suspend fun deleteOwnPrivateMessage(messageId: String): Result<Unit>
}

interface SafetyRepository {
    suspend fun getBlockedUsers(): Result<List<BlockedUser>>
    suspend fun blockUser(targetUserId: String): Result<Unit>
    suspend fun unblockUser(targetUserId: String): Result<Unit>
    suspend fun report(targetUserId: String, messageId: String?, reason: String, details: String?): Result<Unit>
}

interface CallRepository {
    fun observeCallHistory(): Flow<List<CallHistoryItem>>
    suspend fun refreshCallHistory(filter: CallHistoryFilter = CallHistoryFilter.ALL): Result<List<CallHistoryItem>>
    suspend fun fetchIceServers(): Result<Pair<List<IceServerConfig>, Boolean>>
    suspend fun initiateCall(conversationId: String, calleeId: String, isVideo: Boolean): Result<String>
    suspend fun updateCallState(callId: String, action: String): Result<Unit>
    suspend fun getCallStatus(callId: String): Result<String?> = Result.success(null)
}
