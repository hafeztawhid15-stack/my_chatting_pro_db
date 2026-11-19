package com.mychattingpro.data.repository

import android.content.Context
import android.net.Uri
import android.os.Build
import com.mychattingpro.core.common.AppError
import com.mychattingpro.core.common.Country
import com.mychattingpro.core.database.ConversationDao
import com.mychattingpro.core.database.ConversationEntity
import com.mychattingpro.core.database.MessageDao
import com.mychattingpro.core.database.MessageEntity
import com.mychattingpro.core.network.BlockUserRequestDto
import com.mychattingpro.core.network.CloudflareApiService
import com.mychattingpro.core.network.ConversationDto
import com.mychattingpro.core.network.CreateCallRequestDto
import com.mychattingpro.core.network.DeleteAccountRequestDto
import com.mychattingpro.core.network.DirectConversationRequestDto
import com.mychattingpro.core.network.LoginRequestDto
import com.mychattingpro.core.network.MessageDto
import com.mychattingpro.core.network.ProgressRequestBody
import com.mychattingpro.core.network.RealtimeEvent
import com.mychattingpro.core.network.RealtimeSocketManager
import com.mychattingpro.core.network.ReceiptsRequestDto
import com.mychattingpro.core.network.RegisterRequestDto
import com.mychattingpro.core.network.ReportRequestDto
import com.mychattingpro.core.network.SendMessageRequestDto
import com.mychattingpro.core.network.UpdateCallStateRequestDto
import com.mychattingpro.core.network.UpdateProfileRequestDto
import com.mychattingpro.core.security.EncryptedTokenStore
import com.mychattingpro.core.security.StoredSession
import com.mychattingpro.domain.models.ActiveDeviceSession
import com.mychattingpro.domain.models.BlockedUser
import com.mychattingpro.domain.models.Conversation
import com.mychattingpro.domain.models.IceServerConfig
import com.mychattingpro.domain.models.MediaAttachment
import com.mychattingpro.domain.models.Message
import com.mychattingpro.domain.models.MessageStatus
import com.mychattingpro.domain.models.MessageType
import com.mychattingpro.domain.models.OwnerActivitySnapshot
import com.mychattingpro.domain.models.OwnerActivitySummary
import com.mychattingpro.domain.models.OwnerTrackedUser
import com.mychattingpro.domain.models.PublicUser
import com.mychattingpro.domain.models.UserProfile
import com.mychattingpro.domain.repository.AuthRepository
import com.mychattingpro.domain.repository.CallRepository
import com.mychattingpro.domain.repository.ChatRepository
import com.mychattingpro.domain.repository.MessageRepository
import com.mychattingpro.domain.repository.OwnerRepository
import com.mychattingpro.domain.repository.ProfileRepository
import com.mychattingpro.domain.repository.SafetyRepository
import com.mychattingpro.features.media.MediaUploadHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
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
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val socketManager: RealtimeSocketManager,
    private val errorMapper: ErrorMapper
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
        val versionName = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0"
        }.getOrDefault("1.0.0")

        val response = api.register(
            RegisterRequestDto(
                phone = phoneE164,
                countryIso2 = country.iso2,
                callingCode = country.callingCode,
                pin = pin,
                confirmPin = confirmPin,
                displayName = displayName,
                deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".take(60),
                appVersion = versionName
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
        UserProfile(
            id = response.user.id,
            phoneE164 = response.user.phoneE164,
            displayName = response.user.displayName,
            about = response.user.about,
            avatarMediaId = response.user.avatarMediaId
        )
    }.recoverCatching { throw AppException(errorMapper.map(it)) }

    override suspend fun login(phoneE164: String, pin: String): Result<UserProfile> = runCatching {
        val versionName = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0"
        }.getOrDefault("1.0.0")

        val response = api.login(
            LoginRequestDto(
                phone = phoneE164,
                pin = pin,
                deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".take(60),
                appVersion = versionName
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
        UserProfile(
            id = response.user.id,
            phoneE164 = response.user.phoneE164,
            displayName = response.user.displayName,
            about = response.user.about,
            avatarMediaId = response.user.avatarMediaId
        )
    }.recoverCatching { throw AppException(errorMapper.map(it)) }

    override suspend fun logout(): Result<Unit> = runCatching {
        runCatching { api.logout() }
        socketManager.stop()
        tokenStore.clearSession()
        conversationDao.clearAll()
        messageDao.clearAll()
    }

    override suspend fun deleteAccount(pin: String): Result<String> = runCatching {
        val response = api.deleteAccount(DeleteAccountRequestDto(pin = pin))
        if (!response.deleted) {
            throw AppException(AppError.ServerError("Account deletion was not confirmed by server."))
        }
        socketManager.stop()
        tokenStore.clearSession()
        conversationDao.clearAll()
        messageDao.clearAll()
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
    private val errorMapper: ErrorMapper
) : ChatRepository {

    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // Keep Room cache synchronized automatically with Cloudflare Durable Object WebSocket events
        syncScope.launch {
            socketManager.events.collect { event ->
                when (event) {
                    is RealtimeEvent.MessageNew -> {
                        val msg = event.message
                        val myId = tokenStore.getSession()?.userId
                        messageDao.upsert(msg.toEntity(myId))
                        // Acknowledge delivery automatically when received over active WebSocket
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
                    is RealtimeEvent.MessageDelivered -> {
                        messageDao.markDelivered(event.messageIds, event.atMs)
                    }
                    is RealtimeEvent.MessageRead -> {
                        messageDao.markRead(event.messageIds, event.atMs)
                    }
                    is RealtimeEvent.MessageDeleted -> {
                        messageDao.markDeletedForAll(event.messageId, event.deletedAtMs)
                        runCatching { refreshConversations() }
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
        conversationDao.upsertAll(resp.conversations.map { it.toEntity() })
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
}

class MessageRepositoryImpl(
    private val context: Context,
    private val api: CloudflareApiService,
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
    private val tokenStore: EncryptedTokenStore,
    private val errorMapper: ErrorMapper
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
        clientMessageId: String?
    ): Result<Message> {
        val myId = tokenStore.getSession()?.userId ?: return Result.failure(AppException(AppError.WrongPin))
        val cid = clientMessageId ?: UUID.randomUUID().toString()
        val existing = messageDao.getByClientId(cid)
        val tempId = existing?.id ?: "pending_$cid"
        val pendingTs = existing?.createdAtMs ?: System.currentTimeMillis()

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
            readAtMs = null
        )
        messageDao.upsert(pendingEntity)

        return runCatching {
            val resp = api.sendMessage(
                SendMessageRequestDto(
                    conversationId = conversationId,
                    clientMessageId = cid,
                    messageType = "text",
                    bodyText = text
                )
            )
            // Replace temporary pending ID with authoritative server ID & canonical UTC timestamp
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
        onProgress: (Float) -> Unit
    ): Result<Message> {
        val myId = tokenStore.getSession()?.userId ?: return Result.failure(AppException(AppError.WrongPin))
        val cid = existingClientMessageId ?: UUID.randomUUID().toString()
        val existing = messageDao.getByClientId(cid)
        val tempId = existing?.id ?: "pending_$cid"
        val pendingTs = existing?.createdAtMs ?: System.currentTimeMillis()

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
            readAtMs = null
        )
        messageDao.upsert(pendingEntity)

        var tempFileToDelete: File? = null
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
                throw AppException(AppError.StorageFailure)
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
                    mediaId = uploadResp.media.id
                )
            )
            if (tempId != msgResp.message.id) {
                messageDao.deleteById(tempId)
            }
            val canonical = msgResp.message.toEntity(myId)
            messageDao.upsert(canonical)
            canonical.toDomain()
        }.recoverCatching { err ->
            messageDao.upsert(pendingEntity.copy(localStatus = "FAILED", uploadProgress = null))
            throw AppException(errorMapper.map(err))
        }.also {
            if (tempFileToDelete?.parentFile?.name == "temp_media") {
                tempFileToDelete?.delete()
            }
        }
    }

    override suspend fun retryFailedMessage(message: Message, onProgress: (Float) -> Unit): Result<Message> {
        return if (message.type == MessageType.TEXT) {
            sendTextMessage(message.conversationId, message.bodyText.orEmpty(), message.clientMessageId)
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
                onProgress = onProgress
            )
        }
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
    private val errorMapper: ErrorMapper
) : CallRepository {
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

private fun ConversationDto.toEntity() = ConversationEntity(
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
    updatedAtMs = updatedAtMs
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
    updatedAtMs = updatedAtMs
)

private fun MessageDto.toEntity(myUserId: String?): MessageEntity {
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
        readAtMs = readAtMs
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
    readAtMs = readAtMs
)
