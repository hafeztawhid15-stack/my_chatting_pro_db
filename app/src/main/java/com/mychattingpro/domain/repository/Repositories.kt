package com.mychattingpro.domain.repository

import android.net.Uri
import com.mychattingpro.core.common.Country
import com.mychattingpro.domain.models.ActiveDeviceSession
import com.mychattingpro.domain.models.BlockedUser
import com.mychattingpro.domain.models.Conversation
import com.mychattingpro.domain.models.IceServerConfig
import com.mychattingpro.domain.models.MediaAttachment
import com.mychattingpro.domain.models.Message
import com.mychattingpro.domain.models.MessageType
import com.mychattingpro.domain.models.OwnerActivitySnapshot
import com.mychattingpro.domain.models.PublicUser
import com.mychattingpro.domain.models.UserProfile
import kotlinx.coroutines.flow.Flow

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
    suspend fun deleteAccount(pin: String): Result<String>
    suspend fun getActiveSessions(): Result<List<ActiveDeviceSession>>
    suspend fun revokeSession(sessionId: String): Result<Unit>
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
}

interface MessageRepository {
    fun observeMessages(conversationId: String): Flow<List<Message>>
    suspend fun loadLatestMessages(conversationId: String): Result<Boolean>
    suspend fun loadOlderMessages(conversationId: String, beforeTs: Long, beforeId: String): Result<Boolean>
    suspend fun sendTextMessage(conversationId: String, text: String, clientMessageId: String? = null): Result<Message>
    suspend fun sendMediaMessage(
        conversationId: String,
        uri: Uri,
        messageType: MessageType,
        category: String,
        caption: String?,
        durationMs: Long? = null,
        existingClientMessageId: String? = null,
        onProgress: (Float) -> Unit
    ): Result<Message>
    suspend fun retryFailedMessage(message: Message, onProgress: (Float) -> Unit): Result<Message>
    suspend fun markReceipts(conversationId: String, messageIds: List<String>, read: Boolean): Result<Unit>
    suspend fun deleteOwnMessage(messageId: String): Result<Unit>
    suspend fun getSignedMediaUrl(mediaId: String): Result<String>
}

interface SafetyRepository {
    suspend fun getBlockedUsers(): Result<List<BlockedUser>>
    suspend fun blockUser(targetUserId: String): Result<Unit>
    suspend fun unblockUser(targetUserId: String): Result<Unit>
    suspend fun report(targetUserId: String, messageId: String?, reason: String, details: String?): Result<Unit>
}

interface CallRepository {
    suspend fun fetchIceServers(): Result<Pair<List<IceServerConfig>, Boolean>>
    suspend fun initiateCall(conversationId: String, calleeId: String, isVideo: Boolean): Result<String>
    suspend fun updateCallState(callId: String, action: String): Result<Unit>
}
