package com.mychattingpro.data.repository

import android.content.Context
import android.net.Uri
import android.os.Build
import com.example.BuildConfig
import com.mychattingpro.core.common.AppError
import com.mychattingpro.core.common.Country
import com.mychattingpro.core.database.CallHistoryDao
import com.mychattingpro.core.database.CallHistoryEntity
import com.mychattingpro.core.database.ConversationDao
import com.mychattingpro.core.database.ConversationEntity
import com.mychattingpro.core.database.MessageDao
import com.mychattingpro.core.database.MessageEntity
import com.mychattingpro.core.datastore.AppPreferencesDataStore
import com.mychattingpro.core.network.BlockUserRequestDto
import com.mychattingpro.core.network.CallHistoryItemDto
import com.mychattingpro.core.network.ChangeLoginPinRequestDto
import com.mychattingpro.core.network.CloudflareApiService
import com.mychattingpro.core.network.ConversationDto
import com.mychattingpro.core.network.CreateCallRequestDto
import com.mychattingpro.core.network.DeleteAccountRequestDto
import com.mychattingpro.core.network.DirectConversationRequestDto
import com.mychattingpro.core.network.GenerateRecoveryCodeRequestDto
import com.mychattingpro.core.network.LoginRequestDto
import com.mychattingpro.core.network.LogoutAllRequestDto
import com.mychattingpro.core.network.MessageDto
import com.mychattingpro.core.network.NotificationEndpointRequestDto
import com.mychattingpro.core.network.PrivatePinChangeRequestDto
import com.mychattingpro.core.network.PrivatePinResetRequestDto
import com.mychattingpro.core.network.PrivatePinSetupRequestDto
import com.mychattingpro.core.network.PrivatePinUnlockRequestDto
import com.mychattingpro.core.network.ProgressRequestBody
import com.mychattingpro.core.network.RealtimeEvent
import com.mychattingpro.core.network.RealtimeSocketManager
import com.mychattingpro.core.network.ReceiptsRequestDto
import com.mychattingpro.core.network.RegisterRequestDto
import com.mychattingpro.core.network.ReportRequestDto
import com.mychattingpro.core.network.ResetLoginPinRequestDto
import com.mychattingpro.core.network.SendMessageRequestDto
import com.mychattingpro.core.network.UpdateCallStateRequestDto
import com.mychattingpro.core.network.UpdateProfileRequestDto
import com.mychattingpro.core.security.EncryptedTokenStore
import com.mychattingpro.core.security.PrivateChatLockManager
import com.mychattingpro.core.security.StoredSession
import com.mychattingpro.domain.models.ActiveDeviceSession
import com.mychattingpro.domain.models.BlockedUser
import com.mychattingpro.domain.models.CallHistoryFilter
import com.mychattingpro.domain.models.CallHistoryItem
import com.mychattingpro.domain.models.Conversation
import com.mychattingpro.domain.models.ConversationPeerProfile
import com.mychattingpro.domain.models.IceServerConfig
import com.mychattingpro.domain.models.MediaAttachment
import com.mychattingpro.domain.models.Message
import com.mychattingpro.domain.models.MessageStatus
import com.mychattingpro.domain.models.MessageType
import com.mychattingpro.domain.models.OwnerActivitySnapshot
import com.mychattingpro.domain.models.OwnerActivitySummary
import com.mychattingpro.domain.models.OwnerTrackedUser
import com.mychattingpro.domain.models.PrivatePinStatus
import com.mychattingpro.domain.models.PublicUser
import com.mychattingpro.domain.models.UserProfile
import com.mychattingpro.domain.repository.AuthRepository
import com.mychattingpro.domain.repository.CallRepository
import com.mychattingpro.domain.repository.ChatRepository
import com.mychattingpro.domain.repository.MessageRepository
import com.mychattingpro.domain.repository.OwnerRepository
import com.mychattingpro.domain.repository.PrivateChatRepository
import com.mychattingpro.domain.repository.ProfileRepository
import com.mychattingpro.domain.repository.SafetyRepository
import com.mychattingpro.features.media.MediaUploadHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.UUID

class AuthRepositoryImpl(
    private val context: Context,
    private val api: CloudflareApiService,
    private val tokenStore: EncryptedTokenStore,
    private val prefsStore: AppPreferencesDataStore,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val callHistoryDao: CallHistoryDao,
    private val socketManager: RealtimeSocketManager,
    private val errorMapper: ErrorMapper,
    private val privateChatLockManager: PrivateChatLockManager? = null
) : AuthRepository {

    override suspend fun checkPhoneAvailable(phoneE164: String): Result<Boolean> = runCatching {
        val response = api.checkPhoneAvailable(com.mychattingpro.core.network.CheckPhoneRequestDto(phone = phoneE164))
        if (!response.available) {
            throw AppException(AppError.DuplicateAccount)
        }
        true
    }.recoverCatching { throw AppException(errorMapper.map(it)) }

    override suspend fun register(
        phoneE164: String,
        country: Country,
        pin: String,
        confirmPin: String,
        displayName: String
    ): Result<UserProfile> = runCatching {
        val response = api.register(
            RegisterRequestDto(
                phone = phoneE164,
                countryIso2 = country.iso2,
                callingCode = country.callingCode,
                pin = pin,
                confirmPin = confirmPin,
                displayName = displayName,
                deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".take(60),
                appVersion = BuildConfig.VERSION_NAME
            )
        )
        tokenStore.saveSession(
            StoredSession(
                userId = response.user.id,
                phoneE164 = response.user.phoneE164,
                displayName = response.user.displayName,
                about = response.user.about,
                avatarMediaId = response.user.avatarMediaId,
                accessToken = response.accessToken,
                refreshToken = response.refreshToken
            )
        )
        socketManager.start()
        runCatching { syncNotificationEndpoint() }
        UserProfile(
            id = response.user.id,
            phoneE164 = response.user.phoneE164,
            displayName = response.user.displayName,
            about = response.user.about,
            avatarMediaId = response.user.avatarMediaId
        )
    }.recoverCatching { throw AppException(errorMapper.map(it)) }

    override suspend fun login(phoneE164: String, pin: String): Result<UserProfile> = runCatching {
        val response = api.login(
            LoginRequestDto(
                phone = phoneE164,
                pin = pin,
                deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".take(60),
                appVersion = BuildConfig.VERSION_NAME
            )
        )
        tokenStore.saveSession(
            StoredSession(
                userId = response.user.id,
                phoneE164 = response.user.phoneE164,
                displayName = response.user.displayName,
                about = response.user.about,
                avatarMediaId = response.user.avatarMediaId,
                accessToken = response.accessToken,
                refreshToken = response.refreshToken
            )
        )
        socketManager.start()
        runCatching { syncNotificationEndpoint() }
        UserProfile(
            id = response.user.id,
            phoneE164 = response.user.phoneE164,
            displayName = response.user.displayName,
            about = response.user.about,
            avatarMediaId = response.user.avatarMediaId
        )
    }.recoverCatching { throw AppException(errorMapper.map(it)) }

    override suspend fun logout(): Result<Unit> = runCatching {
        privateChatLockManager?.lock()
        runCatching { api.unregisterNotificationEndpoint() }
        runCatching { api.logout() }
        socketManager.stop()
        tokenStore.clearSession()
        conversationDao.clearAll()
        messageDao.clearAll()
        callHistoryDao.clearAll()
    }

    override suspend fun logoutAllDevices(keepCurrent: Boolean): Result<Unit> = runCatching {
        if (!keepCurrent) {
            runCatching { api.unregisterNotificationEndpoint() }
        }
        api.logoutAllDevices(LogoutAllRequestDto(keepCurrent = keepCurrent))
        if (!keepCurrent) {
            privateChatLockManager?.lock()
            socketManager.stop()
            tokenStore.clearSession()
            conversationDao.clearAll()
            messageDao.clearAll()
            callHistoryDao.clearAll()
        }
        Unit
    }.recoverCatching { throw AppException(errorMapper.map(it)) }

    override suspend fun changeLoginPin(
        currentPin: String,
        newPin: String,
        confirmNewPin: String
    ): Result<Unit> = runCatching {
        if (currentPin.length != 5 || !currentPin.all { it.isDigit() } ||
            newPin.length != 5 || !newPin.all { it.isDigit() }
        ) {
            throw AppException(AppError.InvalidPin)
        }
        if (newPin != confirmNewPin) {
            throw AppException(AppError.PinMismatch)
        }
        api.changeLoginPin(
            ChangeLoginPinRequestDto(
                currentPin = currentPin,
                newPin = newPin,
                confirmNewPin = confirmNewPin
            )
        )
        Unit
    }.recoverCatching { throw AppException(errorMapper.map(it)) }

    override suspend fun generateRecoveryCode(currentPin: String): Result<String> = runCatching {
        if (currentPin.length != 5 || !currentPin.all { it.isDigit() }) {
            throw AppException(AppError.InvalidPin)
        }
        val res = api.generateRecoveryCode(GenerateRecoveryCodeRequestDto(currentPin = currentPin))
        prefsStore.setRecoveryCodeConfigured(true)
        res.recoveryCode
    }.recoverCatching { throw AppException(errorMapper.map(it)) }

    override suspend fun resetLoginPinWithRecoveryCode(
        phoneE164: String,
        recoveryCode: String,
        newPin: String,
        confirmNewPin: String
    ): Result<UserProfile> = runCatching {
        if (newPin.length != 5 || !newPin.all { it.isDigit() }) {
            throw AppException(AppError.InvalidPin)
        }
        if (newPin != confirmNewPin) {
            throw AppException(AppError.PinMismatch)
        }
        val cleanedCode = recoveryCode.trim().uppercase()
        if (cleanedCode.replace("-", "").length < 10) {
            throw AppException(AppError.InvalidRecoveryCode)
        }
        val response = api.resetLoginPinWithRecoveryCode(
            ResetLoginPinRequestDto(
                phone = phoneE164,
                recoveryCode = cleanedCode,
                newPin = newPin,
                confirmNewPin = confirmNewPin,
                deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".take(60),
                appVersion = BuildConfig.VERSION_NAME
            )
        )
        tokenStore.saveSession(
            StoredSession(
                userId = response.user.id,
                phoneE164 = response.user.phoneE164,
                displayName = response.user.displayName,
                about = response.user.about,
                avatarMediaId = response.user.avatarMediaId,
                accessToken = response.accessToken,
                refreshToken = response.refreshToken
            )
        )
        socketManager.start()
        runCatching { syncNotificationEndpoint() }
        UserProfile(
            id = response.user.id,
            phoneE164 = response.user.phoneE164,
            displayName = response.user.displayName,
            about = response.user.about,
            avatarMediaId = response.user.avatarMediaId
        )
    }.recoverCatching { throw AppException(errorMapper.map(it)) }

    override suspend fun deleteAccount(pin: String, confirmText: String): Result<String> = runCatching {
        if (confirmText.trim().uppercase() != "DELETE") {
            throw AppException(AppError.InvalidDeleteConfirmation)
        }
        runCatching { api.unregisterNotificationEndpoint() }
        val response = api.deleteAccount(
            DeleteAccountRequestDto(
                pin = pin,
                confirmText = confirmText.trim().uppercase()
            )
        )
        if (!response.deleted) {
            throw AppException(AppError.ServerError("Account deletion was not confirmed by server."))
        }
        privateChatLockManager?.lock()
        socketManager.stop()
        tokenStore.clearSession()
        conversationDao.clearAll()
        messageDao.clearAll()
        callHistoryDao.clearAll()
        prefsStore.setRecoveryCodeConfigured(false)
        response.retentionNotice
    }.recoverCatching { throw AppException(errorMapper.map(it)) }

    override suspend fun getActiveSessions(): Result<List<ActiveDeviceSession>> = runCatching {
        api.listActiveSessions().sessions.map { s ->
            ActiveDeviceSession(
                sessionId = s.sessionId,
                deviceId = s.deviceId,
                deviceName = s.deviceName,
                platform = s.platform,
                appVersion = s.appVersion,
                lastUsedAtMs = s.lastUsedAtMs,
                createdAtMs = s.createdAtMs,
                isCurrent = s.isCurrent
            )
        }
    }.recoverCatching { throw AppException(errorMapper.map(it)) }

    override suspend fun revokeSession(sessionId: String): Result<Unit> = runCatching {
        api.revokeSession(sessionId)
    }.recoverCatching { throw AppException(errorMapper.map(it)) }

    override suspend fun syncNotificationEndpoint(): Result<Unit> = runCatching {
        if (tokenStore.getSession() == null) return@runCatching
        val prefs = prefsStore.current()
        if (!prefs.notificationsEnabled) {
            api.unregisterNotificationEndpoint()
            return@runCatching
        }
        val useUnifiedPush = prefs.unifiedPushEnabled && prefs.unifiedPushEndpointUrl.startsWith("https://")
        api.registerNotificationEndpoint(
            NotificationEndpointRequestDto(
                transport = if (useUnifiedPush) "unified_push" else "websocket_foreground",
                endpointUrl = if (useUnifiedPush) prefs.unifiedPushEndpointUrl else null
            )
        )
    }.recoverCatching { throw AppException(errorMapper.map(it)) }
}

class ProfileRepositoryImpl(
    private val context: Context,
    private val api: CloudflareApiService,
    private val tokenStore: EncryptedTokenStore,
    private val errorMapper: ErrorMapper
) : ProfileRepository {

    override suspend fun fetchMyProfile(): Result<UserProfile> = runCatching {
        val dto = api.getMyProfile()
        tokenStore.updateUserProfile(dto.displayName, dto.about, dto.avatarMediaId)
        UserProfile(
            id = dto.id,
            phoneE164 = dto.phoneE164,
            displayName = dto.displayName,
            about = dto.about,
            avatarMediaId = dto.avatarMediaId,
            isOnline = dto.isOnline,
            lastSeenMs = dto.lastSeenMs
        )
    }.recoverCatching { throw AppException(errorMapper.map(it)) }

    override suspend fun updateProfile(
        displayName: String,
        about: String,
        avatarMediaId: String?
    ): Result<UserProfile> = runCatching {
        val dto = api.updateMyProfile(
            UpdateProfileRequestDto(
                displayName = displayName,
                about = about,
                avatarMediaId = avatarMediaId
            )
        )
        tokenStore.updateUserProfile(dto.displayName, dto.about, dto.avatarMediaId)
        UserProfile(
            id = dto.id,
            phoneE164 = dto.phoneE164,
            displayName = dto.displayName,
            about = dto.about,
            avatarMediaId = dto.avatarMediaId
        )
    }.recoverCatching { throw AppException(errorMapper.map(it)) }

    override suspend fun uploadProfilePhoto(uri: Uri): Result<MediaAttachment> = runCatching {
        val prepared = MediaUploadHelper.prepareImageForUpload(context, uri, maxDimension = 800, quality = 85)
        try {
            val part = MultipartBody.Part.createFormData(
                "file",
                prepared.filename,
                ProgressRequestBody(prepared.file, prepared.mimeType.toMediaTypeOrNull()) {}
            )
            val catBody = "profile".toRequestBody("text/plain".toMediaTypeOrNull())
            val resp = api.uploadMedia(part, catBody, null, null)
            MediaAttachment(
                id = resp.media.id,
                category = resp.media.category,
                originalFilename = resp.media.originalFilename,
                mimeType = resp.media.mimeType,
                sizeBytes = resp.media.sizeBytes
            )
        } finally {
            prepared.file.delete()
        }
    }.recoverCatching { throw AppException(errorMapper.map(it)) }
}

class ChatRepositoryImpl(
    private val api: CloudflareApiService,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val socketManager: RealtimeSocketManager,
    private val tokenStore: EncryptedTokenStore,
    private val errorMapper: ErrorMapper,
    private val privateChatLockManager: PrivateChatLockManager? = null
) : ChatRepository {

    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        syncScope.launch {
            socketManager.events.collect { event ->
                when (event) {
                    is RealtimeEvent.MessageNew -> {
                        val msg = event.message
                        val myId = tokenStore.getSession()?.userId
                        val localConv = conversationDao.getById(msg.conversationId)
                        val effectivePrivate = msg.isPrivate || (localConv?.isPrivate == true)
                        if (effectivePrivate) {
                            if (privateChatLockManager?.currentTokenOrNull() != null) {
                                messageDao.upsert(msg.toEntity(myId, forcePrivate = true))
                                if (myId != null && msg.senderId != myId) {
                                    runCatching {
                                        api.markPrivateReceipts(
                                            ReceiptsRequestDto(
                                                conversationId = msg.conversationId,
                                                messageIds = listOf(msg.id),
                                                type = "delivered"
                                            )
                                        )
                                    }
                                }
                                runCatching {
                                    val resp = api.listPrivateConversations()
                                    val entities = resp.conversations.map { it.toEntity(forcePrivate = true) }
                                    if (entities.isEmpty()) {
                                        conversationDao.deleteAllByPrivacy(isPrivate = true)
                                    } else {
                                        conversationDao.deleteMissingByPrivacy(
                                            isPrivate = true,
                                            activeIds = entities.map { it.id }
                                        )
                                        conversationDao.upsertAll(entities)
                                        entities.forEach { conversationDao.deleteRegularCopiesForPeer(it.peerUserId) }
                                    }
                                }
                            }
                        } else {
                            messageDao.upsert(msg.toEntity(myId, forcePrivate = false))
                            if (myId != null && msg.senderId != myId) {
                                runCatching {
                                    api.markReceipts(
                                        ReceiptsRequestDto(
                                            conversationId = msg.conversationId,
                                            messageIds = listOf(msg.id),
                                            type = "delivered"
                                        )
                                    )
                                }
                            }
                            runCatching { refreshConversations() }
                        }
                    }
                    is RealtimeEvent.MessageDelivered -> {
                        messageDao.markDelivered(event.messageIds, event.atMs)
                    }
                    is RealtimeEvent.MessageRead -> {
                        messageDao.markRead(event.messageIds, event.atMs)
                    }
                    is RealtimeEvent.MessageDeleted -> {
                        messageDao.markDeletedForAll(event.messageId, event.deletedAtMs)
                        val deletedMsg = messageDao.getById(event.messageId)
                        val localConv = deletedMsg?.let { conversationDao.getById(it.conversationId) }
                        val effectivePrivate = event.isPrivate ||
                            (localConv?.isPrivate == true) ||
                            (deletedMsg?.isPrivate == true)
                        if (effectivePrivate) {
                            if (privateChatLockManager?.currentTokenOrNull() != null) {
                                runCatching {
                                    val resp = api.listPrivateConversations()
                                    val entities = resp.conversations.map { it.toEntity(forcePrivate = true) }
                                    if (entities.isEmpty()) {
                                        conversationDao.deleteAllByPrivacy(isPrivate = true)
                                    } else {
                                        conversationDao.deleteMissingByPrivacy(
                                            isPrivate = true,
                                            activeIds = entities.map { it.id }
                                        )
                                        conversationDao.upsertAll(entities)
                                        entities.forEach { conversationDao.deleteRegularCopiesForPeer(it.peerUserId) }
                                    }
                                }
                            }
                        } else {
                            runCatching { refreshConversations() }
                        }
                    }
                    is RealtimeEvent.PresenceChanged -> {
                        conversationDao.updatePeerPresence(event.userId, event.isOnline, event.lastSeenMs)
                    }
                    else -> Unit
                }
            }
        }
    }

    override fun observeConversations(): Flow<List<Conversation>> =
        conversationDao.observeConversations().map { list -> list.map { it.toDomain() } }

    override fun observeConversation(conversationId: String): Flow<Conversation?> =
        conversationDao.observeConversation(conversationId).map { it?.toDomain() }

    override suspend fun refreshConversations(): Result<Unit> = runCatching {
        val resp = api.listConversations()
        val entities = resp.conversations.map { it.toEntity(forcePrivate = false) }
        if (entities.isEmpty()) {
            conversationDao.deleteAllByPrivacy(isPrivate = false)
        } else {
            conversationDao.deleteMissingByPrivacy(isPrivate = false, activeIds = entities.map { it.id })
            conversationDao.upsertAll(entities)
        }
    }.recoverCatching { throw AppException(errorMapper.map(it)) }

    override suspend fun searchUser(phoneE164: String, nameFilter: String?): Result<PublicUser?> = runCatching {
        val resp = api.searchUsers(phoneE164, nameFilter?.takeIf { it.isNotBlank() })
        val u = resp.user
        if (!resp.found || u == null) {
            null
        } else {
            PublicUser(
                id = u.id,
                displayName = u.displayName,
                about = u.about,
                avatarMediaId = u.avatarMediaId,
                isOnline = u.isOnline,
                lastSeenMs = u.lastSeenMs
            )
        }
    }.recoverCatching { throw AppException(errorMapper.map(it)) }

    override suspend fun startDirectChat(targetUserId: String): Result<Conversation> = runCatching {
        val resp = api.createOrGetDirectConversation(DirectConversationRequestDto(targetUserId))
        val entity = resp.conversation.toEntity()
        conversationDao.upsert(entity)
        entity.toDomain()
    }.recoverCatching { throw AppException(errorMapper.map(it)) }

    override suspend fun setPinned(conversationId: String, pinned: Boolean): Result<Unit> = runCatching {
        api.updateConversationPreferences(conversationId, mapOf("isPinned" to pinned))
        conversationDao.updatePinned(conversationId, pinned, if (pinned) System.currentTimeMillis() else null)
    }.recoverCatching { throw AppException(errorMapper.map(it)) }

    override suspend fun setArchived(conversationId: String, archived: Boolean): Result<Unit> = runCatching {
        api.updateConversationPreferences(conversationId, mapOf("isArchived" to archived))
        conversationDao.updateArchived(conversationId, archived, if (archived) System.currentTimeMillis() else null)
    }.recoverCatching { throw AppException(errorMapper.map(it)) }

    override suspend fun setMutedUntil(conversationId: String, mutedUntilMs: Long?): Result<Unit> = runCatching {
        api.updateConversationPreferences(conversationId, mapOf("mutedUntilMs" to (mutedUntilMs ?: 0L)))
        conversationDao.updateMutedUntil(conversationId, mutedUntilMs?.takeIf { it > 0L })
    }.recoverCatching { throw AppException(errorMapper.map(it)) }

    override suspend fun setCustomSound(
        conversationId: String,
        soundUri: String?,
        soundLabel: String?
    ): Result<Unit> = runCatching {
        api.updateConversationPreferences(
            conversationId,
            mapOf("customSoundUri" to (soundUri ?: ""), "customSoundLabel" to (soundLabel ?: ""))
        )
        conversationDao.updateCustomSound(
            conversationId,
            soundUri?.takeIf { it.isNotBlank() },
            soundLabel?.takeIf { it.isNotBlank() }
        )
    }.recoverCatching { throw AppException(errorMapper.map(it)) }

    override suspend fun setMarkedUnread(conversationId: String, unread: Boolean): Result<Unit> = runCatching {
        api.updateConversationPreferences(conversationId, mapOf("markedUnread" to unread))
        conversationDao.updateMarkedUnread(conversationId, unread)
    }.recoverCatching { throw AppException(errorMapper.map(it)) }

    override suspend fun clearConversation(conversationId: String): Result<Unit> = runCatching {
        val res = api.clearConversation(conversationId)
        messageDao.clearConversationMessages(conversationId, isPrivate = false)
        conversationDao.clearLastMessageSummary(conversationId, res.clearedAtMs)
    }.recoverCatching { throw AppException(errorMapper.map(it)) }

    override suspend fun deleteConversation(conversationId: String): Result<Unit> = runCatching {
        api.deleteConversation(conversationId)
        messageDao.clearConversationMessages(conversationId, isPrivate = false)
        conversationDao.deleteById(conversationId)
    }.recoverCatching { throw AppException(errorMapper.map(it)) }

    override suspend fun moveToPrivateChat(conversationId: String): Result<Unit> = runCatching {
        api.moveConversationToPrivate(conversationId)
        conversationDao.updatePrivateState(conversationId, true)
        conversationDao.updatePinned(conversationId, false, null)
        conversationDao.updateArchived(conversationId, false, null)
        messageDao.updateConversationMessagesPrivateState(conversationId, true)
    }.recoverCatching { throw AppException(errorMapper.map(it)) }

    override suspend fun getPeerProfile(conversationId: String): Result<ConversationPeerProfile> = runCatching {
        val res = api.getConversationPeerProfile(conversationId)
        ConversationPeerProfile(
            user = PublicUser(
                id = res.user.id,
                displayName = res.user.displayName,
                about = res.user.about,
                avatarMediaId = res.user.avatarMediaId,
                isOnline = res.user.isOnline,
                lastSeenMs = res.user.lastSeenMs
            ),
            isBlockedByMe = res.isBlockedByMe,
            hasBlockedMe = res.hasBlockedMe
        )
    }.recoverCatching { throw AppException(errorMapper.map(it)) }
}

class MessageRepositoryImpl(
    private val context: Context,
    private val api: CloudflareApiService,
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
    private val tokenStore: EncryptedTokenStore,
    private val errorMapper: ErrorMapper,
    private val privateChatLockManager: PrivateChatLockManager? = null
) : MessageRepository {

    override fun observeMessages(conversationId: String): Flow<List<Message>> =
        messageDao.observeMessages(conversationId).map { list -> list.map { it.toDomain() } }

    override suspend fun loadLatestMessages(conversationId: String): Result<Boolean> = runCatching {
        val page = api.listMessages(conversationId, limit = 40)
        val myId = tokenStore.getSession()?.userId
        messageDao.upsertAll(page.messages.map { it.toEntity(myId) })
        page.hasMore
    }.recoverCatching { throw AppException(errorMapper.map(it)) }

    override suspend fun loadOlderMessages(
        conversationId: String,
        beforeTs: Long,
        beforeId: String
    ): Result<Boolean> = runCatching {
        val page = api.listMessages(conversationId, beforeTs = beforeTs, beforeId = beforeId, limit = 40)
        val myId = tokenStore.getSession()?.userId
        messageDao.upsertAll(page.messages.map { it.toEntity(myId) })
        page.hasMore
    }.recoverCatching { throw AppException(errorMapper.map(it)) }

    override suspend fun sendTextMessage(
        conversationId: String,
        text: String,
        clientMessageId: String?,
        replyToMessageId: String?
    ): Result<Message> {
        val myId = tokenStore.getSession()?.userId ?: return Result.failure(AppException(AppError.SessionRevoked))
        val cid = clientMessageId ?: UUID.randomUUID().toString()
        val existing = messageDao.getByClientId(cid)
        val tempId = existing?.id ?: "pending_$cid"
        val pendingTs = existing?.createdAtMs ?: System.currentTimeMillis()
        val resolvedReplyId = replyToMessageId ?: existing?.replyToMessageId

        val pendingEntity = MessageEntity(
            id = tempId,
            conversationId = conversationId,
            senderId = myId,
            clientMessageId = cid,
            messageType = "text",
            bodyText = text,
            mediaId = null,
            mediaCategory = null,
            mediaFilename = null,
            mediaMimeType = null,
            mediaSizeBytes = null,
            mediaDurationMs = null,
            localStatus = "SENDING",
            localUri = null,
            uploadProgress = null,
            deletedForAllAtMs = null,
            createdAtMs = pendingTs,
            deliveredAtMs = null,
            readAtMs = null,
            replyToMessageId = resolvedReplyId
        )
        messageDao.upsert(pendingEntity)

        return runCatching {
            val resp = api.sendMessage(
                SendMessageRequestDto(
                    conversationId = conversationId,
                    clientMessageId = cid,
                    messageType = "text",
                    bodyText = text,
                    replyToMessageId = resolvedReplyId
                )
            )
            if (tempId != resp.message.id) {
                messageDao.deleteById(tempId)
            }
            val canonical = resp.message.toEntity(myId)
            messageDao.upsert(canonical)
            canonical.toDomain()
        }.recoverCatching { err ->
            messageDao.upsert(pendingEntity.copy(localStatus = "FAILED"))
            throw AppException(errorMapper.map(err))
        }
    }

    override suspend fun sendMediaMessage(
        conversationId: String,
        uri: Uri,
        messageType: MessageType,
        category: String,
        caption: String?,
        durationMs: Long?,
        existingClientMessageId: String?,
        replyToMessageId: String?,
        onProgress: (Float) -> Unit
    ): Result<Message> {
        val myId = tokenStore.getSession()?.userId ?: return Result.failure(AppException(AppError.SessionRevoked))
        val cid = existingClientMessageId ?: UUID.randomUUID().toString()
        val existing = messageDao.getByClientId(cid)
        val tempId = existing?.id ?: "pending_$cid"
        val pendingTs = existing?.createdAtMs ?: System.currentTimeMillis()
        val resolvedReplyId = replyToMessageId ?: existing?.replyToMessageId

        val pendingEntity = MessageEntity(
            id = tempId,
            conversationId = conversationId,
            senderId = myId,
            clientMessageId = cid,
            messageType = messageType.name.lowercase(),
            bodyText = caption,
            mediaId = null,
            mediaCategory = category,
            mediaFilename = uri.lastPathSegment ?: "media",
            mediaMimeType = null,
            mediaSizeBytes = null,
            mediaDurationMs = durationMs,
            localStatus = "SENDING",
            localUri = uri.toString(),
            uploadProgress = 0.05f,
            deletedForAllAtMs = null,
            createdAtMs = pendingTs,
            deliveredAtMs = null,
            readAtMs = null,
            replyToMessageId = resolvedReplyId
        )
        messageDao.upsert(pendingEntity)

        var tempFileToDelete: File? = null
        var succeeded = false
        return runCatching {
            val prepared = if (messageType == MessageType.IMAGE) {
                MediaUploadHelper.prepareImageForUpload(context, uri)
            } else {
                MediaUploadHelper.copyUriToTempFile(context, uri)
            }
            tempFileToDelete = prepared.file

            val maxAllowedBytes = when (category) {
                "image", "profile" -> 10L * 1024L * 1024L
                "audio" -> 15L * 1024L * 1024L
                else -> MediaUploadHelper.MAX_UPLOAD_BYTES
            }
            if (prepared.sizeBytes <= 0L) {
                throw AppException(AppError.EmptyMediaFile)
            }
            if (prepared.sizeBytes > maxAllowedBytes) {
                throw AppException(AppError.MediaTooLarge((maxAllowedBytes / (1024L * 1024L)).toInt()))
            }

            messageDao.upsert(
                pendingEntity.copy(
                    mediaFilename = prepared.filename,
                    mediaMimeType = prepared.mimeType,
                    mediaSizeBytes = prepared.sizeBytes
                )
            )

            val part = MultipartBody.Part.createFormData(
                "file",
                prepared.filename,
                ProgressRequestBody(prepared.file, prepared.mimeType.toMediaTypeOrNull()) { p ->
                    onProgress(p)
                }
            )
            val catBody = category.toRequestBody("text/plain".toMediaTypeOrNull())
            val convBody = conversationId.toRequestBody("text/plain".toMediaTypeOrNull())
            val durBody = durationMs?.toString()?.toRequestBody("text/plain".toMediaTypeOrNull())

            val uploadResp = api.uploadMedia(part, catBody, convBody, durBody)
            val msgResp = api.sendMessage(
                SendMessageRequestDto(
                    conversationId = conversationId,
                    clientMessageId = cid,
                    messageType = messageType.name.lowercase(),
                    bodyText = caption,
                    mediaId = uploadResp.media.id,
                    replyToMessageId = resolvedReplyId
                )
            )
            if (tempId != msgResp.message.id) {
                messageDao.deleteById(tempId)
            }
            val canonical = msgResp.message.toEntity(myId)
            messageDao.upsert(canonical)
            succeeded = true
            canonical.toDomain()
        }.recoverCatching { err ->
            messageDao.upsert(pendingEntity.copy(localStatus = "FAILED", uploadProgress = null))
            throw AppException(errorMapper.map(err))
        }.also {
            if (tempFileToDelete?.parentFile?.name == "temp_media") {
                tempFileToDelete?.delete()
            }
            if (succeeded && uri.scheme == "file") {
                val orig = uri.path?.let { File(it) }
                if (orig != null && orig.parentFile?.name == "voice" && orig.exists()) {
                    orig.delete()
                }
            }
        }
    }

    override suspend fun retryFailedMessage(message: Message, onProgress: (Float) -> Unit): Result<Message> {
        return if (message.type == MessageType.TEXT) {
            sendTextMessage(
                conversationId = message.conversationId,
                text = message.bodyText.orEmpty(),
                clientMessageId = message.clientMessageId,
                replyToMessageId = message.replyToMessageId
            )
        } else {
            val uriStr = message.localUri ?: return Result.failure(AppException(AppError.StorageFailure))
            val cat = when (message.type) {
                MessageType.IMAGE -> "image"
                MessageType.VIDEO -> "video"
                MessageType.VOICE, MessageType.AUDIO -> "audio"
                else -> "file"
            }
            sendMediaMessage(
                conversationId = message.conversationId,
                uri = Uri.parse(uriStr),
                messageType = message.type,
                category = cat,
                caption = message.bodyText,
                durationMs = message.media?.durationMs,
                existingClientMessageId = message.clientMessageId,
                replyToMessageId = message.replyToMessageId,
                onProgress = onProgress
            )
        }
    }

    override suspend fun retryAllPendingMessages(): Int {
        if (tokenStore.getSession() == null) return 0
        val pending = messageDao.getPendingOrFailedMessages()
        var retriedCount = 0
        for (entity in pending) {
            if (entity.isPrivate && privateChatLockManager?.currentTokenOrNull() == null) {
                continue
            }
            val domain = entity.toDomain()
            if (entity.isPrivate) {
                // Only retry text in background for private chats if unlocked
                if (domain.type == MessageType.TEXT) {
                    val res = runCatching {
                        api.sendPrivateMessage(
                            SendMessageRequestDto(
                                conversationId = entity.conversationId,
                                clientMessageId = entity.clientMessageId,
                                messageType = "text",
                                bodyText = entity.bodyText,
                                replyToMessageId = entity.replyToMessageId
                            )
                        )
                    }
                    if (res.isSuccess) {
                        val msg = res.getOrThrow().message
                        if (entity.id != msg.id) messageDao.deleteById(entity.id)
                        messageDao.upsert(msg.toEntity(entity.senderId, forcePrivate = true))
                        retriedCount++
                    }
                }
            } else {
                val result = retryFailedMessage(domain) {}
                if (result.isSuccess) retriedCount++
            }
        }
        return retriedCount
    }

    override suspend fun markReceipts(conversationId: String, messageIds: List<String>, read: Boolean): Result<Unit> =
        runCatching {
            if (messageIds.isEmpty()) return@runCatching
            api.markReceipts(
                ReceiptsRequestDto(
                    conversationId = conversationId,
                    messageIds = messageIds,
                    type = if (read) "read" else "delivered"
                )
            )
            if (read) {
                conversationDao.clearUnreadCount(conversationId)
            }
        }

    override suspend fun deleteOwnMessage(messageId: String): Result<Unit> = runCatching {
        api.deleteMessage(messageId)
        messageDao.markDeletedForAll(messageId, System.currentTimeMillis())
    }.recoverCatching { throw AppException(errorMapper.map(it)) }

    override suspend fun getSignedMediaUrl(mediaId: String): Result<String> = runCatching {
        api.getSignedMediaUrl(mediaId).downloadUrl
    }.recoverCatching { throw AppException(errorMapper.map(it)) }
}

class SafetyRepositoryImpl(
    private val api: CloudflareApiService,
    private val conversationDao: ConversationDao,
    private val errorMapper: ErrorMapper
) : SafetyRepository {
    override suspend fun getBlockedUsers(): Result<List<BlockedUser>> = runCatching {
        api.getBlockedUsers().blockedUsers.map {
            BlockedUser(it.userId, it.displayName, it.avatarMediaId, it.blockedAtMs)
        }
    }.recoverCatching { throw AppException(errorMapper.map(it)) }

    override suspend fun blockUser(targetUserId: String): Result<Unit> = runCatching {
        api.blockUser(BlockUserRequestDto(targetUserId))
        conversationDao.updateBlockedByMe(targetUserId, true)
    }.recoverCatching { throw AppException(errorMapper.map(it)) }

    override suspend fun unblockUser(targetUserId: String): Result<Unit> = runCatching {
        api.unblockUser(targetUserId)
        conversationDao.updateBlockedByMe(targetUserId, false)
    }.recoverCatching { throw AppException(errorMapper.map(it)) }

    override suspend fun report(
        targetUserId: String,
        messageId: String?,
        reason: String,
        details: String?
    ): Result<Unit> = runCatching {
        api.submitReport(ReportRequestDto(targetUserId, messageId, reason, details))
    }.recoverCatching { throw AppException(errorMapper.map(it)) }
}

class CallRepositoryImpl(
    private val api: CloudflareApiService,
    private val callHistoryDao: CallHistoryDao,
    private val errorMapper: ErrorMapper
) : CallRepository {

    override fun observeCallHistory(): Flow<List<CallHistoryItem>> =
        callHistoryDao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun refreshCallHistory(filter: CallHistoryFilter): Result<List<CallHistoryItem>> = runCatching {
        val res = api.getCallHistory(filter = filter.apiValue, limit = 80)
        val entities = res.calls.map { it.toEntity() }
        if (filter == CallHistoryFilter.ALL) {
            callHistoryDao.upsertAll(entities)
        }
        entities.map { it.toDomain() }
    }.recoverCatching { throw AppException(errorMapper.map(it)) }

    override suspend fun fetchIceServers(): Result<Pair<List<IceServerConfig>, Boolean>> = runCatching {
        val resp = api.getIceServers()
        resp.iceServers.map { IceServerConfig(it.urls, it.username, it.credential) } to resp.turnConfigured
    }.recoverCatching { throw AppException(errorMapper.map(it)) }

    override suspend fun initiateCall(conversationId: String, calleeId: String, isVideo: Boolean): Result<String> =
        runCatching {
            val resp = api.initiateCall(
                CreateCallRequestDto(
                    conversationId = conversationId,
                    calleeId = calleeId,
                    callType = if (isVideo) "video" else "audio"
                )
            )
            resp.call.callId
        }.recoverCatching { throw AppException(errorMapper.map(it)) }

    override suspend fun updateCallState(callId: String, action: String): Result<Unit> = runCatching {
        api.updateCallState(callId, UpdateCallStateRequestDto(action))
    }.recoverCatching { throw AppException(errorMapper.map(it)) }

    override suspend fun getCallStatus(callId: String): Result<String?> = runCatching {
        api.getCallStatus(callId).status
    }.recoverCatching { throw AppException(errorMapper.map(it)) }
}

class OwnerRepositoryImpl(
    private val api: CloudflareApiService,
    private val errorMapper: ErrorMapper
) : OwnerRepository {
    override suspend fun fetchOwnerActivity(
        ownerKey: String,
        query: String,
        page: Int,
        limit: Int
    ): Result<OwnerActivitySnapshot> = runCatching {
        val resp = api.getOwnerActivity(
            ownerKey = ownerKey,
            query = query.trim().takeIf { it.isNotEmpty() },
            page = page.coerceAtLeast(1),
            limit = limit.coerceIn(1, 50)
        )
        OwnerActivitySnapshot(
            summary = OwnerActivitySummary(
                totalRegisteredUsers = resp.summary.totalRegisteredUsers,
                currentlyOnlineUsers = resp.summary.currentlyOnlineUsers,
                activeLast24hUsers = resp.summary.activeLast24hUsers,
                activeSessionsCount = resp.summary.activeSessionsCount,
                generatedAtMs = resp.summary.generatedAtMs
            ),
            users = resp.users.map { u ->
                OwnerTrackedUser(
                    userId = u.userId,
                    displayName = u.displayName,
                    countryIso2 = u.countryIso2,
                    maskedPhone = u.maskedPhone,
                    isOnline = u.isOnline,
                    lastSeenMs = u.lastSeenMs,
                    createdAtMs = u.createdAtMs,
                    latestDeviceName = u.latestDeviceName,
                    latestPlatform = u.latestPlatform,
                    latestAppVersion = u.latestAppVersion,
                    activeSessionCount = u.activeSessionCount
                )
            },
            page = resp.pagination?.page ?: page,
            totalCount = resp.pagination?.totalCount ?: resp.users.size,
            hasMore = resp.pagination?.hasMore ?: false
        )
    }.recoverCatching { throw AppException(errorMapper.map(it)) }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PrivateChatRepositoryImpl(
    private val context: Context,
    private val api: CloudflareApiService,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val tokenStore: EncryptedTokenStore,
    private val lockManager: PrivateChatLockManager,
    private val errorMapper: ErrorMapper
) : PrivateChatRepository {

    override val isUnlocked: StateFlow<Boolean> = lockManager.isUnlocked

    override fun lockPrivateChat() {
        lockManager.lock()
    }

    override fun touchActivity() {
        lockManager.touchActivity()
    }

    override suspend fun getPinStatus(): Result<PrivatePinStatus> = runCatching {
        val dto = api.getPrivatePinStatus()
        PrivatePinStatus(
            hasPrivatePin = dto.hasPrivatePin,
            lockedUntilMs = dto.lockedUntilMs,
            retryAfterSeconds = dto.retryAfterSeconds
        )
    }.recoverCatching { throw AppException(errorMapper.map(it)) }

    override suspend fun setupPrivatePin(pin: String, confirmPin: String): Result<Unit> = runCatching {
        if (pin.length != 5 || !pin.all { it.isDigit() }) {
            throw AppException(AppError.InvalidPin)
        }
        if (pin != confirmPin) {
            throw AppException(AppError.PinMismatch)
        }
        val resp = api.setupPrivatePin(PrivatePinSetupRequestDto(pin = pin, confirmPin = confirmPin))
        lockManager.unlock(resp.privateUnlockToken, resp.expiresInSeconds)
    }.recoverCatching { throw AppException(errorMapper.map(it)) }

    override suspend fun unlockPrivateChat(pin: String): Result<Unit> = runCatching {
        if (pin.length != 5 || !pin.all { it.isDigit() }) {
            throw AppException(AppError.InvalidPin)
        }
        val resp = api.unlockPrivateChat(PrivatePinUnlockRequestDto(pin = pin))
        lockManager.unlock(resp.privateUnlockToken, resp.expiresInSeconds)
    }.recoverCatching { throw AppException(errorMapper.map(it)) }

    override suspend fun changePrivatePin(
        oldPin: String,
        newPin: String,
        confirmNewPin: String
    ): Result<Unit> = runCatching {
        if (oldPin.length != 5 || !oldPin.all { it.isDigit() } || newPin.length != 5 || !newPin.all { it.isDigit() }) {
            throw AppException(AppError.InvalidPin)
        }
        if (newPin != confirmNewPin) {
            throw AppException(AppError.PinMismatch)
        }
        val resp = api.changePrivatePin(
            PrivatePinChangeRequestDto(
                oldPin = oldPin,
                newPin = newPin,
                confirmNewPin = confirmNewPin
            )
        )
        if (lockManager.currentTokenOrNull() != null) {
            lockManager.unlock(resp.privateUnlockToken, resp.expiresInSeconds)
        }
    }.recoverCatching { throw AppException(errorMapper.map(it)) }

    override suspend fun resetPrivatePinWithAccountAuth(
        loginPin: String,
        newPrivatePin: String,
        confirmNewPrivatePin: String
    ): Result<Unit> = runCatching {
        if (loginPin.length != 5 || !loginPin.all { it.isDigit() } || newPrivatePin.length != 5 || !newPrivatePin.all { it.isDigit() }) {
            throw AppException(AppError.InvalidPin)
        }
        if (newPrivatePin != confirmNewPrivatePin) {
            throw AppException(AppError.PinMismatch)
        }
        val resp = api.resetPrivatePin(
            PrivatePinResetRequestDto(
                loginPin = loginPin,
                newPrivatePin = newPrivatePin,
                confirmNewPrivatePin = confirmNewPrivatePin
            )
        )
        lockManager.unlock(resp.privateUnlockToken, resp.expiresInSeconds)
    }.recoverCatching { throw AppException(errorMapper.map(it)) }

    override fun observePrivateConversations(): Flow<List<Conversation>> =
        lockManager.isUnlocked.flatMapLatest { unlocked ->
            if (!unlocked) flowOf(emptyList())
            else conversationDao.observePrivateConversations().map { list -> list.map { it.toDomain() } }
        }

    override fun observePrivateConversation(conversationId: String): Flow<Conversation?> =
        lockManager.isUnlocked.flatMapLatest { unlocked ->
            if (!unlocked) flowOf(null)
            else conversationDao.observeConversation(conversationId).map { entity ->
                entity?.takeIf { it.isPrivate }?.toDomain()
            }
        }

    override suspend fun refreshPrivateConversations(): Result<Unit> = runCatching {
        requireUnlocked()
        lockManager.touchActivity()
        val resp = api.listPrivateConversations()
        val entities = resp.conversations.map { it.toEntity(forcePrivate = true) }
        if (entities.isEmpty()) {
            conversationDao.deleteAllByPrivacy(isPrivate = true)
        } else {
            conversationDao.deleteMissingByPrivacy(isPrivate = true, activeIds = entities.map { it.id })
            conversationDao.upsertAll(entities)
            entities.forEach { conversationDao.deleteRegularCopiesForPeer(it.peerUserId) }
        }
    }.recoverCatching { throw AppException(errorMapper.map(it)) }

    override suspend fun searchUserForPrivateChat(phoneE164: String, nameFilter: String?): Result<PublicUser?> =
        runCatching {
            requireUnlocked()
            lockManager.touchActivity()
            val resp = api.searchUsersForPrivateChat(phoneE164, nameFilter?.takeIf { it.isNotBlank() })
            val u = resp.user
            if (!resp.found || u == null) {
                null
            } else {
                PublicUser(
                    id = u.id,
                    displayName = u.displayName,
                    about = u.about,
                    avatarMediaId = u.avatarMediaId,
                    isOnline = u.isOnline,
                    lastSeenMs = u.lastSeenMs
                )
            }
        }.recoverCatching { throw AppException(errorMapper.map(it)) }

    override suspend fun startPrivateDirectChat(targetUserId: String): Result<Conversation> = runCatching {
        requireUnlocked()
        lockManager.touchActivity()
        val resp = api.createOrGetPrivateDirectConversation(DirectConversationRequestDto(targetUserId))
        val entity = resp.conversation.toEntity(forcePrivate = true)
        conversationDao.deleteRegularCopiesForPeer(entity.peerUserId)
        conversationDao.upsert(entity)
        messageDao.updateConversationMessagesPrivateState(entity.id, true)
        entity.toDomain()
    }.recoverCatching { throw AppException(errorMapper.map(it)) }

    override suspend fun setPrivatePinned(conversationId: String, pinned: Boolean): Result<Unit> = runCatching {
        requireUnlocked()
        lockManager.touchActivity()
        api.updatePrivateConversationPreferences(conversationId, mapOf("isPinned" to pinned))
        conversationDao.updatePinned(conversationId, pinned, if (pinned) System.currentTimeMillis() else null)
    }.recoverCatching { throw AppException(errorMapper.map(it)) }

    override suspend fun setPrivateArchived(conversationId: String, archived: Boolean): Result<Unit> = runCatching {
        requireUnlocked()
        lockManager.touchActivity()
        api.updatePrivateConversationPreferences(conversationId, mapOf("isArchived" to archived))
        conversationDao.updateArchived(conversationId, archived, if (archived) System.currentTimeMillis() else null)
    }.recoverCatching { throw AppException(errorMapper.map(it)) }

    override suspend fun setPrivateMutedUntil(conversationId: String, mutedUntilMs: Long?): Result<Unit> = runCatching {
        requireUnlocked()
        lockManager.touchActivity()
        api.updatePrivateConversationPreferences(conversationId, mapOf("mutedUntilMs" to (mutedUntilMs ?: 0L)))
        conversationDao.updateMutedUntil(conversationId, mutedUntilMs?.takeIf { it > 0L })
    }.recoverCatching { throw AppException(errorMapper.map(it)) }

    override suspend fun setPrivateCustomSound(
        conversationId: String,
        soundUri: String?,
        soundLabel: String?
    ): Result<Unit> = runCatching {
        requireUnlocked()
        lockManager.touchActivity()
        api.updatePrivateConversationPreferences(
            conversationId,
            mapOf("customSoundUri" to (soundUri ?: ""), "customSoundLabel" to (soundLabel ?: ""))
        )
        conversationDao.updateCustomSound(
            conversationId,
            soundUri?.takeIf { it.isNotBlank() },
            soundLabel?.takeIf { it.isNotBlank() }
        )
    }.recoverCatching { throw AppException(errorMapper.map(it)) }

    override suspend fun setPrivateMarkedUnread(conversationId: String, unread: Boolean): Result<Unit> = runCatching {
        requireUnlocked()
        lockManager.touchActivity()
        api.updatePrivateConversationPreferences(conversationId, mapOf("markedUnread" to unread))
        conversationDao.updateMarkedUnread(conversationId, unread)
    }.recoverCatching { throw AppException(errorMapper.map(it)) }

    override suspend fun clearPrivateConversation(conversationId: String): Result<Unit> = runCatching {
        requireUnlocked()
        lockManager.touchActivity()
        val res = api.clearPrivateConversation(conversationId)
        messageDao.clearConversationMessages(conversationId, isPrivate = true)
        conversationDao.clearLastMessageSummary(conversationId, res.clearedAtMs)
    }.recoverCatching { throw AppException(errorMapper.map(it)) }

    override suspend fun deletePrivateConversation(conversationId: String): Result<Unit> = runCatching {
        requireUnlocked()
        lockManager.touchActivity()
        api.deletePrivateConversation(conversationId)
        messageDao.clearConversationMessages(conversationId, isPrivate = true)
        conversationDao.deleteById(conversationId)
    }.recoverCatching { throw AppException(errorMapper.map(it)) }

    override suspend fun moveToRegularChat(conversationId: String): Result<Unit> = runCatching {
        requireUnlocked()
        lockManager.touchActivity()
        api.movePrivateConversationToRegular(conversationId)
        conversationDao.updatePrivateState(conversationId, false)
        conversationDao.updatePinned(conversationId, false, null)
        conversationDao.updateArchived(conversationId, false, null)
        messageDao.updateConversationMessagesPrivateState(conversationId, false)
    }.recoverCatching { throw AppException(errorMapper.map(it)) }

    override suspend fun getPrivatePeerProfile(conversationId: String): Result<ConversationPeerProfile> = runCatching {
        requireUnlocked()
        lockManager.touchActivity()
        val res = api.getPrivateConversationPeerProfile(conversationId)
        ConversationPeerProfile(
            user = PublicUser(
                id = res.user.id,
                displayName = res.user.displayName,
                about = res.user.about,
                avatarMediaId = res.user.avatarMediaId,
                isOnline = res.user.isOnline,
                lastSeenMs = res.user.lastSeenMs
            ),
            isBlockedByMe = res.isBlockedByMe,
            hasBlockedMe = res.hasBlockedMe
        )
    }.recoverCatching { throw AppException(errorMapper.map(it)) }

    override fun observePrivateMessages(conversationId: String): Flow<List<Message>> =
        lockManager.isUnlocked.flatMapLatest { unlocked ->
            if (!unlocked) flowOf(emptyList())
            else messageDao.observePrivateMessages(conversationId).map { list -> list.map { it.toDomain() } }
        }

    override suspend fun loadLatestPrivateMessages(conversationId: String): Result<Boolean> = runCatching {
        requireUnlocked()
        lockManager.touchActivity()
        val page = api.listPrivateMessages(conversationId, limit = 40)
        val myId = tokenStore.getSession()?.userId
        messageDao.upsertAll(page.messages.map { it.toEntity(myId, forcePrivate = true) })
        page.hasMore
    }.recoverCatching { throw AppException(errorMapper.map(it)) }

    override suspend fun loadOlderPrivateMessages(
        conversationId: String,
        beforeTs: Long,
        beforeId: String
    ): Result<Boolean> = runCatching {
        requireUnlocked()
        lockManager.touchActivity()
        val page = api.listPrivateMessages(conversationId, beforeTs = beforeTs, beforeId = beforeId, limit = 40)
        val myId = tokenStore.getSession()?.userId
        messageDao.upsertAll(page.messages.map { it.toEntity(myId, forcePrivate = true) })
        page.hasMore
    }.recoverCatching { throw AppException(errorMapper.map(it)) }

    override suspend fun sendPrivateTextMessage(
        conversationId: String,
        text: String,
        clientMessageId: String?,
        replyToMessageId: String?
    ): Result<Message> {
        if (lockManager.currentTokenOrNull() == null) {
            return Result.failure(AppException(AppError.PrivateChatLocked))
        }
        lockManager.touchActivity()
        val myId = tokenStore.getSession()?.userId ?: return Result.failure(AppException(AppError.SessionRevoked))
        val cid = clientMessageId ?: UUID.randomUUID().toString()
        val existing = messageDao.getByClientId(cid)
        val tempId = existing?.id ?: "pending_$cid"
        val pendingTs = existing?.createdAtMs ?: System.currentTimeMillis()
        val resolvedReplyId = replyToMessageId ?: existing?.replyToMessageId

        val pendingEntity = MessageEntity(
            id = tempId,
            conversationId = conversationId,
            senderId = myId,
            clientMessageId = cid,
            messageType = "text",
            bodyText = text,
            mediaId = null,
            mediaCategory = null,
            mediaFilename = null,
            mediaMimeType = null,
            mediaSizeBytes = null,
            mediaDurationMs = null,
            localStatus = "SENDING",
            localUri = null,
            uploadProgress = null,
            deletedForAllAtMs = null,
            createdAtMs = pendingTs,
            deliveredAtMs = null,
            readAtMs = null,
            isPrivate = true,
            replyToMessageId = resolvedReplyId
        )
        messageDao.upsert(pendingEntity)

        return runCatching {
            val resp = api.sendPrivateMessage(
                SendMessageRequestDto(
                    conversationId = conversationId,
                    clientMessageId = cid,
                    messageType = "text",
                    bodyText = text,
                    replyToMessageId = resolvedReplyId
                )
            )
            if (tempId != resp.message.id) {
                messageDao.deleteById(tempId)
            }
            val canonical = resp.message.toEntity(myId, forcePrivate = true)
            messageDao.upsert(canonical)
            canonical.toDomain()
        }.recoverCatching { err ->
            messageDao.upsert(pendingEntity.copy(localStatus = "FAILED"))
            throw AppException(errorMapper.map(err))
        }
    }

    override suspend fun sendPrivateMediaMessage(
        conversationId: String,
        uri: Uri,
        messageType: MessageType,
        category: String,
        caption: String?,
        durationMs: Long?,
        existingClientMessageId: String?,
        replyToMessageId: String?,
        onProgress: (Float) -> Unit
    ): Result<Message> {
        if (lockManager.currentTokenOrNull() == null) {
            return Result.failure(AppException(AppError.PrivateChatLocked))
        }
        lockManager.touchActivity()
        val myId = tokenStore.getSession()?.userId ?: return Result.failure(AppException(AppError.SessionRevoked))
        val cid = existingClientMessageId ?: UUID.randomUUID().toString()
        val existing = messageDao.getByClientId(cid)
        val tempId = existing?.id ?: "pending_$cid"
        val pendingTs = existing?.createdAtMs ?: System.currentTimeMillis()
        val resolvedReplyId = replyToMessageId ?: existing?.replyToMessageId

        val pendingEntity = MessageEntity(
            id = tempId,
            conversationId = conversationId,
            senderId = myId,
            clientMessageId = cid,
            messageType = messageType.name.lowercase(),
            bodyText = caption,
            mediaId = null,
            mediaCategory = category,
            mediaFilename = uri.lastPathSegment ?: "media",
            mediaMimeType = null,
            mediaSizeBytes = null,
            mediaDurationMs = durationMs,
            localStatus = "SENDING",
            localUri = uri.toString(),
            uploadProgress = 0.05f,
            deletedForAllAtMs = null,
            createdAtMs = pendingTs,
            deliveredAtMs = null,
            readAtMs = null,
            isPrivate = true,
            replyToMessageId = resolvedReplyId
        )
        messageDao.upsert(pendingEntity)

        var tempFileToDelete: File? = null
        var succeeded = false
        return runCatching {
            val prepared = if (messageType == MessageType.IMAGE) {
                MediaUploadHelper.prepareImageForUpload(context, uri)
            } else {
                MediaUploadHelper.copyUriToTempFile(context, uri)
            }
            tempFileToDelete = prepared.file

            val maxAllowedBytes = when (category) {
                "image", "profile" -> 10L * 1024L * 1024L
                "audio" -> 15L * 1024L * 1024L
                else -> MediaUploadHelper.MAX_UPLOAD_BYTES
            }
            if (prepared.sizeBytes <= 0L) {
                throw AppException(AppError.EmptyMediaFile)
            }
            if (prepared.sizeBytes > maxAllowedBytes) {
                throw AppException(AppError.MediaTooLarge((maxAllowedBytes / (1024L * 1024L)).toInt()))
            }

            messageDao.upsert(
                pendingEntity.copy(
                    mediaFilename = prepared.filename,
                    mediaMimeType = prepared.mimeType,
                    mediaSizeBytes = prepared.sizeBytes
                )
            )

            val part = MultipartBody.Part.createFormData(
                "file",
                prepared.filename,
                ProgressRequestBody(prepared.file, prepared.mimeType.toMediaTypeOrNull()) { p ->
                    onProgress(p)
                }
            )
            val catBody = category.toRequestBody("text/plain".toMediaTypeOrNull())
            val convBody = conversationId.toRequestBody("text/plain".toMediaTypeOrNull())
            val durBody = durationMs?.toString()?.toRequestBody("text/plain".toMediaTypeOrNull())

            val uploadResp = api.uploadMedia(part, catBody, convBody, durBody)
            val msgResp = api.sendPrivateMessage(
                SendMessageRequestDto(
                    conversationId = conversationId,
                    clientMessageId = cid,
                    messageType = messageType.name.lowercase(),
                    bodyText = caption,
                    mediaId = uploadResp.media.id,
                    replyToMessageId = resolvedReplyId
                )
            )
            if (tempId != msgResp.message.id) {
                messageDao.deleteById(tempId)
            }
            val canonical = msgResp.message.toEntity(myId, forcePrivate = true)
            messageDao.upsert(canonical)
            succeeded = true
            canonical.toDomain()
        }.recoverCatching { err ->
            messageDao.upsert(pendingEntity.copy(localStatus = "FAILED", uploadProgress = null))
            throw AppException(errorMapper.map(err))
        }.also {
            if (tempFileToDelete?.parentFile?.name == "temp_media") {
                tempFileToDelete?.delete()
            }
            if (succeeded && uri.scheme == "file") {
                val orig = uri.path?.let { File(it) }
                if (orig != null && orig.parentFile?.name == "voice" && orig.exists()) {
                    orig.delete()
                }
            }
        }
    }

    override suspend fun retryFailedPrivateMessage(
        message: Message,
        onProgress: (Float) -> Unit
    ): Result<Message> {
        return if (message.type == MessageType.TEXT) {
            sendPrivateTextMessage(
                conversationId = message.conversationId,
                text = message.bodyText.orEmpty(),
                clientMessageId = message.clientMessageId,
                replyToMessageId = message.replyToMessageId
            )
        } else {
            val uriStr = message.localUri ?: return Result.failure(AppException(AppError.StorageFailure))
            val cat = when (message.type) {
                MessageType.IMAGE -> "image"
                MessageType.VIDEO -> "video"
                MessageType.VOICE, MessageType.AUDIO -> "audio"
                else -> "file"
            }
            sendPrivateMediaMessage(
                conversationId = message.conversationId,
                uri = Uri.parse(uriStr),
                messageType = message.type,
                category = cat,
                caption = message.bodyText,
                durationMs = message.media?.durationMs,
                existingClientMessageId = message.clientMessageId,
                replyToMessageId = message.replyToMessageId,
                onProgress = onProgress
            )
        }
    }

    override suspend fun markPrivateReceipts(
        conversationId: String,
        messageIds: List<String>,
        read: Boolean
    ): Result<Unit> = runCatching {
        if (messageIds.isEmpty()) return@runCatching
        requireUnlocked()
        lockManager.touchActivity()
        api.markPrivateReceipts(
            ReceiptsRequestDto(
                conversationId = conversationId,
                messageIds = messageIds,
                type = if (read) "read" else "delivered"
            )
        )
        if (read) {
            conversationDao.clearUnreadCount(conversationId)
        }
    }

    override suspend fun deleteOwnPrivateMessage(messageId: String): Result<Unit> = runCatching {
        requireUnlocked()
        lockManager.touchActivity()
        api.deletePrivateMessage(messageId)
        messageDao.markDeletedForAll(messageId, System.currentTimeMillis())
    }.recoverCatching { throw AppException(errorMapper.map(it)) }

    private fun requireUnlocked() {
        if (lockManager.currentTokenOrNull() == null) {
            throw AppException(AppError.PrivateChatLocked)
        }
    }
}

private fun ConversationDto.toEntity(forcePrivate: Boolean = isPrivate) = ConversationEntity(
    id = id,
    type = type,
    peerUserId = peerUserId,
    peerDisplayName = peerDisplayName,
    peerAvatarMediaId = peerAvatarMediaId,
    peerAbout = peerAbout,
    peerIsOnline = peerIsOnline,
    peerLastSeenMs = peerLastSeenMs,
    isBlockedByMe = isBlockedByMe,
    hasBlockedMe = hasBlockedMe,
    lastMessageId = lastMessageId,
    lastMessagePreview = lastMessagePreview,
    lastMessageType = lastMessageType,
    lastMessageSenderId = lastMessageSenderId,
    lastMessageAtMs = lastMessageAtMs,
    unreadCount = unreadCount,
    updatedAtMs = updatedAtMs,
    isPrivate = forcePrivate || isPrivate,
    isPinned = isPinned,
    pinnedAtMs = pinnedAtMs,
    isArchived = isArchived,
    archivedAtMs = archivedAtMs,
    mutedUntilMs = mutedUntilMs?.takeIf { it > 0L },
    customSoundUri = customSoundUri?.takeIf { it.isNotBlank() },
    customSoundLabel = customSoundLabel?.takeIf { it.isNotBlank() },
    markedUnread = markedUnread
)

private fun ConversationEntity.toDomain() = Conversation(
    id = id,
    type = type,
    peerUserId = peerUserId,
    peerDisplayName = peerDisplayName,
    peerAvatarMediaId = peerAvatarMediaId,
    peerAbout = peerAbout,
    peerIsOnline = peerIsOnline,
    peerLastSeenMs = peerLastSeenMs,
    isBlockedByMe = isBlockedByMe,
    hasBlockedMe = hasBlockedMe,
    lastMessageId = lastMessageId,
    lastMessagePreview = lastMessagePreview,
    lastMessageType = lastMessageType?.let { MessageType.fromApi(it) },
    lastMessageSenderId = lastMessageSenderId,
    lastMessageAtMs = lastMessageAtMs,
    unreadCount = unreadCount,
    updatedAtMs = updatedAtMs,
    isPrivate = isPrivate,
    isPinned = isPinned,
    pinnedAtMs = pinnedAtMs,
    isArchived = isArchived,
    archivedAtMs = archivedAtMs,
    mutedUntilMs = mutedUntilMs?.takeIf { it > 0L },
    customSoundUri = customSoundUri?.takeIf { it.isNotBlank() },
    customSoundLabel = customSoundLabel?.takeIf { it.isNotBlank() },
    markedUnread = markedUnread,
    clearedAtMs = clearedAtMs
)

private fun MessageDto.toEntity(myUserId: String?, forcePrivate: Boolean = isPrivate): MessageEntity {
    val status = when {
        readAtMs != null -> "READ"
        deliveredAtMs != null -> "DELIVERED"
        else -> "SENT"
    }
    return MessageEntity(
        id = id,
        conversationId = conversationId,
        senderId = senderId,
        clientMessageId = clientMessageId,
        messageType = messageType,
        bodyText = bodyText,
        mediaId = media?.id,
        mediaCategory = media?.category,
        mediaFilename = media?.originalFilename,
        mediaMimeType = media?.mimeType,
        mediaSizeBytes = media?.sizeBytes,
        mediaDurationMs = media?.durationMs,
        localStatus = if (senderId == myUserId) status else "DELIVERED",
        localUri = null,
        uploadProgress = null,
        deletedForAllAtMs = deletedForAllAtMs,
        createdAtMs = createdAtMs,
        deliveredAtMs = deliveredAtMs,
        readAtMs = readAtMs,
        isPrivate = forcePrivate || isPrivate,
        replyToMessageId = replyToMessageId
    )
}

private fun MessageEntity.toDomain() = Message(
    id = id,
    conversationId = conversationId,
    senderId = senderId,
    clientMessageId = clientMessageId,
    type = MessageType.fromApi(messageType),
    bodyText = bodyText,
    media = if (mediaId != null && mediaFilename != null && mediaMimeType != null) {
        MediaAttachment(
            id = mediaId,
            category = mediaCategory ?: "file",
            originalFilename = mediaFilename,
            mimeType = mediaMimeType,
            sizeBytes = mediaSizeBytes ?: 0L,
            durationMs = mediaDurationMs
        )
    } else {
        null
    },
    status = MessageStatus.fromApi(localStatus),
    localUri = localUri,
    uploadProgress = uploadProgress,
    deletedForAllAtMs = deletedForAllAtMs,
    createdAtMs = createdAtMs,
    deliveredAtMs = deliveredAtMs,
    readAtMs = readAtMs,
    isPrivate = isPrivate,
    replyToMessageId = replyToMessageId
)

private fun CallHistoryItemDto.toEntity() = CallHistoryEntity(
    callId = callId,
    conversationId = conversationId,
    callerId = callerId,
    peerUserId = peerUserId,
    peerDisplayName = peerDisplayName,
    peerAvatarMediaId = peerAvatarMediaId,
    callType = callType,
    status = status,
    direction = direction,
    startedAtMs = startedAtMs,
    answeredAtMs = answeredAtMs,
    endedAtMs = endedAtMs,
    durationSeconds = durationSeconds
)

private fun CallHistoryEntity.toDomain() = CallHistoryItem(
    callId = callId,
    conversationId = conversationId,
    callerId = callerId,
    peerUserId = peerUserId,
    peerDisplayName = peerDisplayName,
    peerAvatarMediaId = peerAvatarMediaId,
    isVideo = callType == "video",
    status = status,
    direction = direction,
    startedAtMs = startedAtMs,
    answeredAtMs = answeredAtMs,
    endedAtMs = endedAtMs,
    durationSeconds = durationSeconds
)
