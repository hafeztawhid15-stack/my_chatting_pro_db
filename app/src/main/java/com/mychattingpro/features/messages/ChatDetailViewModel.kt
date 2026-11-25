package com.mychattingpro.features.messages

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.R
import com.mychattingpro.core.common.AppError
import com.mychattingpro.core.datastore.AppPreferencesDataStore
import com.mychattingpro.core.localization.AppLanguage
import com.mychattingpro.core.network.NetworkMonitor
import com.mychattingpro.core.network.RealtimeEvent
import com.mychattingpro.core.network.RealtimeSocketManager
import com.mychattingpro.core.network.SocketConnectionState
import com.mychattingpro.core.security.EncryptedTokenStore
import com.mychattingpro.data.repository.AppException
import com.mychattingpro.domain.models.CallHistoryFilter
import com.mychattingpro.domain.models.CallHistoryItem
import com.mychattingpro.domain.models.Conversation
import com.mychattingpro.domain.models.Message
import com.mychattingpro.domain.models.MessageType
import com.mychattingpro.domain.repository.CallRepository
import com.mychattingpro.domain.repository.ChatRepository
import com.mychattingpro.domain.repository.MessageRepository
import com.mychattingpro.domain.repository.PrivateChatRepository
import com.mychattingpro.domain.repository.SafetyRepository
import com.mychattingpro.features.common.PeerProfileModalState
import com.mychattingpro.features.media.MediaUploadHelper
import com.mychattingpro.features.media.VoiceRecorderManager
import com.mychattingpro.features.media.VoiceRecordingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

data class ChatDetailUiState(
    val composerText: String = "",
    val replyingToMessage: Message? = null,
    val isLoadingInitial: Boolean = true,
    val isLoadingOlder: Boolean = false,
    val hasMoreOlder: Boolean = true,
    val isPeerTyping: Boolean = false,
    val uploadProgressByClientId: Map<String, Float> = emptyMap(),
    val activeAudioMessageId: String? = null,
    val isAudioPlaying: Boolean = false,
    val isAudioPaused: Boolean = false,
    val isAudioLoading: Boolean = false,
    val audioPositionMs: Long = 0L,
    val audioDurationMs: Long = 0L,
    val audioErrorMessageId: String? = null,
    val pendingMediaPreviewUri: Uri? = null,
    val pendingMediaIsVideo: Boolean = false,
    val downloadingMessageId: String? = null,
    val isSharingMessage: Boolean = false,
    val peerProfileModal: PeerProfileModalState? = null,
    val conversationDeleted: Boolean = false,
    val error: AppError? = null,
    val infoToast: String? = null
)

sealed interface ChatTimelineItem {
    val timelineId: String
    val timestampMs: Long

    data class MessageEntry(val message: Message) : ChatTimelineItem {
        override val timelineId: String = "msg_${message.id}"
        override val timestampMs: Long = message.createdAtMs
    }

    data class CallEntry(val call: CallHistoryItem) : ChatTimelineItem {
        override val timelineId: String = "call_${call.callId}"
        override val timestampMs: Long = call.startedAtMs
    }

    companion object {
        fun buildTimeline(
            messages: List<Message>,
            calls: List<CallHistoryItem>
        ): List<ChatTimelineItem> {
            val items = ArrayList<ChatTimelineItem>(messages.size + calls.size)
            messages.forEach { items.add(MessageEntry(it)) }
            calls.forEach { items.add(CallEntry(it)) }
            return items.sortedWith(
                compareBy<ChatTimelineItem>(
                    { it.timestampMs },
                    { it is CallEntry },
                    { it.timelineId }
                )
            )
        }
    }
}

class ChatDetailViewModel(
    private val context: Context,
    val conversationId: String,
    val peerUserId: String,
    private val chatRepository: ChatRepository,
    private val messageRepository: MessageRepository,
    private val safetyRepository: SafetyRepository,
    private val tokenStore: EncryptedTokenStore,
    prefsStore: AppPreferencesDataStore,
    private val socketManager: RealtimeSocketManager,
    private val okHttpClient: OkHttpClient,
    private val networkMonitor: NetworkMonitor,
    val isPrivate: Boolean = false,
    private val privateChatRepository: PrivateChatRepository? = null,
    private val callRepository: CallRepository? = null,
    private val baseUrl: String = BuildConfig.CLOUDFLARE_API_BASE_URL
        .ifBlank { "https://my-chatting-pro-api.workers.dev" }
        .trimEnd('/') + "/"
) : ViewModel() {

    val voiceRecorder = VoiceRecorderManager(context)
    val voiceState: StateFlow<VoiceRecordingState> = voiceRecorder.state

    val isPrivateUnlocked: StateFlow<Boolean> =
        privateChatRepository?.isUnlocked ?: MutableStateFlow(true)

    val conversation: StateFlow<Conversation?> =
        if (isPrivate && privateChatRepository != null) {
            privateChatRepository.observePrivateConversation(conversationId)
        } else {
            chatRepository.observeConversation(conversationId)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val messages: StateFlow<List<Message>> =
        if (isPrivate && privateChatRepository != null) {
            privateChatRepository.observePrivateMessages(conversationId)
        } else {
            messageRepository.observeMessages(conversationId)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val peerCalls: StateFlow<List<CallHistoryItem>> =
        if (!isPrivate && callRepository != null) {
            callRepository.observeCallsForPeer(conversationId, peerUserId)
        } else {
            flowOf(emptyList())
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val timelineItems: StateFlow<List<ChatTimelineItem>> =
        combine(messages, peerCalls) { msgs, calls ->
            ChatTimelineItem.buildTimeline(msgs, calls)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val language: StateFlow<AppLanguage> = prefsStore.preferencesFlow
        .map { it.language }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppLanguage.ENGLISH)

    val isOnline: StateFlow<Boolean> = networkMonitor.isOnline
    val socketState: StateFlow<SocketConnectionState> = socketManager.connectionState

    private val _uiState = MutableStateFlow(ChatDetailUiState())
    val uiState: StateFlow<ChatDetailUiState> = _uiState.asStateFlow()

    var myUserId: String? = null
        private set

    private val uploadJobs = mutableMapOf<String, Job>()
    private val submittedReadIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private var typingTimeoutJob: Job? = null
    private var audioLoadJob: Job? = null
    private var audioProgressJob: Job? = null
    private var audioPlayer: MediaPlayer? = null
    private var playbackFocusRequest: AudioFocusRequest? = null
    private val playbackFocusListener = AudioManager.OnAudioFocusChangeListener { }

    init {
        viewModelScope.launch {
            if (isPrivate && privateChatRepository != null) {
                privateChatRepository.refreshPrivateConversations()
            } else {
                chatRepository.refreshConversations()
                callRepository?.refreshCallHistory(CallHistoryFilter.ALL)
            }
        }

        viewModelScope.launch {
            myUserId = tokenStore.currentSessionOrNull()?.userId ?: tokenStore.getSession()?.userId
            val res = if (isPrivate && privateChatRepository != null) {
                privateChatRepository.loadLatestPrivateMessages(conversationId)
            } else {
                messageRepository.loadLatestMessages(conversationId)
            }
            res.onSuccess { hasMore ->
                _uiState.value = _uiState.value.copy(isLoadingInitial = false, hasMoreOlder = hasMore)
            }.onFailure { err ->
                val appErr = (err as? AppException)?.appError ?: AppError.ServerError()
                _uiState.value = _uiState.value.copy(isLoadingInitial = false, error = appErr)
            }
        }

        // Mark incoming messages read (deduplicated) and reveal cached messages immediately
        viewModelScope.launch {
            messages.collect { list ->
                if (list.isNotEmpty() && _uiState.value.isLoadingInitial) {
                    _uiState.value = _uiState.value.copy(isLoadingInitial = false)
                }
                val mine = myUserId ?: tokenStore.currentSessionOrNull()?.userId ?: return@collect
                val unreadIds = list
                    .filter {
                        it.senderId != mine &&
                            it.readAtMs == null &&
                            !it.id.startsWith("pending_") &&
                            !submittedReadIds.contains(it.id)
                    }
                    .map { it.id }
                if (unreadIds.isNotEmpty()) {
                    submittedReadIds.addAll(unreadIds)
                    val receiptRes = if (isPrivate && privateChatRepository != null) {
                        privateChatRepository.markPrivateReceipts(conversationId, unreadIds, read = true)
                    } else {
                        messageRepository.markReceipts(conversationId, unreadIds, read = true)
                    }
                    receiptRes.onFailure {
                        submittedReadIds.removeAll(unreadIds.toSet())
                    }
                }
            }
        }

        // Listen for peer typing and call state events
        viewModelScope.launch {
            socketManager.events.collect { ev ->
                when (ev) {
                    is RealtimeEvent.TypingChanged -> {
                        if (ev.conversationId == conversationId && ev.fromUserId == peerUserId) {
                            _uiState.value = _uiState.value.copy(isPeerTyping = ev.isTyping)
                        }
                    }
                    is RealtimeEvent.CallStateChanged,
                    is RealtimeEvent.CallMissed -> {
                        if (!isPrivate) {
                            callRepository?.refreshCallHistory(CallHistoryFilter.ALL)
                        }
                    }
                    else -> Unit
                }
            }
        }

        // Presence heartbeat & smart fallback refresh (skip frequent HTTP polling when WS is healthy)
        viewModelScope.launch {
            var pollCycle = 0
            while (isActive) {
                delay(25_000L)
                pollCycle++
                if (networkMonitor.isOnline.value) {
                    socketManager.sendPresencePing()
                    if (!socketManager.isConnected() || pollCycle % 4 == 0) {
                        if (isPrivate && privateChatRepository != null) {
                            privateChatRepository.refreshPrivateConversations()
                        } else {
                            chatRepository.refreshConversations()
                        }
                    }
                }
            }
        }
    }

    fun refreshConversationState() {
        viewModelScope.launch {
            socketManager.sendPresencePing()
            if (isPrivate && privateChatRepository != null) {
                privateChatRepository.refreshPrivateConversations()
            } else {
                chatRepository.refreshConversations()
                callRepository?.refreshCallHistory(CallHistoryFilter.ALL)
            }
        }
    }

    fun touchPrivateActivity() {
        if (isPrivate) {
            privateChatRepository?.touchActivity()
        }
    }

    fun updateComposerText(text: String) {
        touchPrivateActivity()
        _uiState.value = _uiState.value.copy(composerText = text, error = null)
        socketManager.sendTyping(conversationId, peerUserId, text.isNotBlank())
        typingTimeoutJob?.cancel()
        if (text.isNotBlank()) {
            typingTimeoutJob = viewModelScope.launch {
                delay(2500)
                socketManager.sendTyping(conversationId, peerUserId, false)
            }
        }
    }

    fun startReply(message: Message) {
        if (message.deletedForAllAtMs != null || message.id.startsWith("pending_")) return
        touchPrivateActivity()
        _uiState.value = _uiState.value.copy(replyingToMessage = message)
    }

    fun cancelReply() {
        _uiState.value = _uiState.value.copy(replyingToMessage = null)
    }

    fun sendTextMessage() {
        touchPrivateActivity()
        val text = _uiState.value.composerText.trim()
        if (text.isEmpty()) return
        val replyToId = _uiState.value.replyingToMessage?.id
        _uiState.value = _uiState.value.copy(
            composerText = "",
            replyingToMessage = null,
            error = null
        )
        socketManager.sendTyping(conversationId, peerUserId, false)

        viewModelScope.launch {
            val res = if (isPrivate && privateChatRepository != null) {
                privateChatRepository.sendPrivateTextMessage(
                    conversationId = conversationId,
                    text = text,
                    replyToMessageId = replyToId
                )
            } else {
                messageRepository.sendTextMessage(
                    conversationId = conversationId,
                    text = text,
                    replyToMessageId = replyToId
                )
            }
            res.onFailure { err ->
                val appErr = (err as? AppException)?.appError ?: AppError.ServerError()
                _uiState.value = _uiState.value.copy(error = appErr)
            }
        }
    }

    fun showMediaPreview(uri: Uri, isVideo: Boolean) {
        _uiState.value = _uiState.value.copy(
            pendingMediaPreviewUri = uri,
            pendingMediaIsVideo = isVideo
        )
    }

    fun dismissMediaPreview() {
        _uiState.value = _uiState.value.copy(pendingMediaPreviewUri = null)
    }

    fun confirmAndSendPendingMedia(caption: String?) {
        val uri = _uiState.value.pendingMediaPreviewUri ?: return
        val isVideo = _uiState.value.pendingMediaIsVideo
        _uiState.value = _uiState.value.copy(pendingMediaPreviewUri = null)
        sendMediaUri(
            uri = uri,
            messageType = if (isVideo) MessageType.VIDEO else MessageType.IMAGE,
            category = if (isVideo) "video" else "image",
            caption = caption?.trim()?.takeIf { it.isNotEmpty() }
        )
    }

    fun sendMediaUri(
        uri: Uri,
        messageType: MessageType,
        category: String,
        caption: String? = null,
        durationMs: Long? = null
    ) {
        touchPrivateActivity()
        val replyToId = _uiState.value.replyingToMessage?.id
        if (replyToId != null) {
            _uiState.value = _uiState.value.copy(replyingToMessage = null)
        }
        val cid = UUID.randomUUID().toString()
        val job = viewModelScope.launch {
            updateProgress(cid, 0.05f)
            val res = if (isPrivate && privateChatRepository != null) {
                privateChatRepository.sendPrivateMediaMessage(
                    conversationId = conversationId,
                    uri = uri,
                    messageType = messageType,
                    category = category,
                    caption = caption,
                    durationMs = durationMs,
                    existingClientMessageId = cid,
                    replyToMessageId = replyToId,
                    onProgress = { p -> updateProgress(cid, p) }
                )
            } else {
                messageRepository.sendMediaMessage(
                    conversationId = conversationId,
                    uri = uri,
                    messageType = messageType,
                    category = category,
                    caption = caption,
                    durationMs = durationMs,
                    existingClientMessageId = cid,
                    replyToMessageId = replyToId,
                    onProgress = { p -> updateProgress(cid, p) }
                )
            }
            clearProgress(cid)
            uploadJobs.remove(cid)
            res.onFailure { err ->
                val appErr = (err as? AppException)?.appError ?: AppError.StorageFailure
                _uiState.value = _uiState.value.copy(error = appErr)
            }
        }
        uploadJobs[cid] = job
    }

    fun cancelUpload(clientMessageId: String) {
        uploadJobs.remove(clientMessageId)?.cancel()
        clearProgress(clientMessageId)
    }

    fun retryMessage(message: Message) {
        touchPrivateActivity()
        val cid = message.clientMessageId
        val job = viewModelScope.launch {
            if (message.type != MessageType.TEXT) updateProgress(cid, 0.05f)
            val res = if (isPrivate && privateChatRepository != null) {
                privateChatRepository.retryFailedPrivateMessage(message) { p ->
                    updateProgress(cid, p)
                }
            } else {
                messageRepository.retryFailedMessage(message) { p ->
                    updateProgress(cid, p)
                }
            }
            clearProgress(cid)
            uploadJobs.remove(cid)
            res.onFailure { err ->
                val appErr = (err as? AppException)?.appError ?: AppError.ServerError()
                _uiState.value = _uiState.value.copy(error = appErr)
            }
        }
        uploadJobs[cid] = job
    }

    fun loadOlderMessages() {
        val state = _uiState.value
        if (state.isLoadingOlder || !state.hasMoreOlder) return
        val oldest = messages.value.firstOrNull { !it.id.startsWith("pending_") } ?: return
        touchPrivateActivity()

        _uiState.value = state.copy(isLoadingOlder = true)
        viewModelScope.launch {
            val res = if (isPrivate && privateChatRepository != null) {
                privateChatRepository.loadOlderPrivateMessages(conversationId, oldest.createdAtMs, oldest.id)
            } else {
                messageRepository.loadOlderMessages(conversationId, oldest.createdAtMs, oldest.id)
            }
            res.onSuccess { hasMore ->
                _uiState.value = _uiState.value.copy(isLoadingOlder = false, hasMoreOlder = hasMore)
            }.onFailure {
                _uiState.value = _uiState.value.copy(isLoadingOlder = false)
            }
        }
    }

    fun sendRecordedVoiceNote() {
        val result = voiceRecorder.stopRecording()
        if (result == null) {
            val msg = voiceRecorder.state.value.error ?: context.getString(R.string.err_empty_media_file)
            _uiState.value = _uiState.value.copy(error = AppError.ServerError(msg))
            return
        }
        val (file, durationMs) = result
        // Preserve recorded file on disk until upload succeeds or is cancelled
        voiceRecorder.clearRecordedStateWithoutDeleting()
        sendMediaUri(
            uri = Uri.fromFile(file),
            messageType = MessageType.VOICE,
            category = "audio",
            durationMs = durationMs
        )
    }

    fun toggleVoiceMessagePlayback(message: Message) {
        val currentState = _uiState.value
        if (currentState.activeAudioMessageId == message.id) {
            if (currentState.isAudioLoading) return
            val existingPlayer = audioPlayer
            if (existingPlayer != null) {
                if (currentState.isAudioPlaying) {
                    runCatching { existingPlayer.pause() }
                    audioProgressJob?.cancel()
                    audioProgressJob = null
                    _uiState.value = _uiState.value.copy(
                        isAudioPlaying = false,
                        isAudioPaused = true
                    )
                    return
                } else if (currentState.isAudioPaused) {
                    val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    if (am != null) requestPlaybackFocus(am)
                    runCatching { existingPlayer.start() }
                    _uiState.value = _uiState.value.copy(
                        isAudioPlaying = true,
                        isAudioPaused = false,
                        audioErrorMessageId = null
                    )
                    startAudioProgressTicker(message.id)
                    return
                }
            }
        }

        val mediaId = message.media?.id
        val localUriString = message.localUri
        if (mediaId == null && localUriString.isNullOrBlank()) return

        stopAudioPlayback()
        val initialDurationMs = (message.media?.durationMs ?: 0L).coerceAtLeast(0L)
        _uiState.value = _uiState.value.copy(
            activeAudioMessageId = message.id,
            isAudioPlaying = false,
            isAudioPaused = false,
            isAudioLoading = true,
            audioPositionMs = 0L,
            audioDurationMs = initialDurationMs,
            audioErrorMessageId = null,
            error = null
        )

        audioLoadJob = viewModelScope.launch {
            val resolvedFile = withContext(Dispatchers.IO) {
                // 1. Prefer valid local file if available (for instant playback of own or cached notes)
                if (!localUriString.isNullOrBlank()) {
                    val uri = Uri.parse(localUriString)
                    if (uri.scheme == "file" && !uri.path.isNullOrBlank()) {
                        val f = File(uri.path!!)
                        if (f.exists() && f.length() > 0L) return@withContext Result.success(f)
                    }
                }

                if (mediaId == null) {
                    return@withContext Result.failure<File>(IllegalStateException("Missing audio source"))
                }

                val cacheDir = File(context.cacheDir, "voice_cache").apply { mkdirs() }
                val cachedFile = File(cacheDir, "$mediaId.audio")
                if (cachedFile.exists() && cachedFile.length() > 0L) {
                    return@withContext Result.success(cachedFile)
                }

                // 2. Download via authenticated Cloudflare /v1/media/{id}/content (carries JWT & Private unlock token)
                val primaryUrl = "${baseUrl}v1/media/$mediaId/content"
                if (downloadAudioToFile(primaryUrl, cachedFile)) {
                    return@withContext Result.success(cachedFile)
                }

                // 3. Fallback to signed URL download
                val signedUrl = messageRepository.getSignedMediaUrl(mediaId).getOrNull()
                if (!signedUrl.isNullOrBlank() && downloadAudioToFile(signedUrl, cachedFile)) {
                    return@withContext Result.success(cachedFile)
                }

                Result.failure(IllegalStateException("Failed to fetch voice audio"))
            }

            if (_uiState.value.activeAudioMessageId != message.id) return@launch

            resolvedFile.onSuccess { audioFile ->
                startPreparedMediaPlayer(
                    messageId = message.id,
                    configureDataSource = { player ->
                        FileInputStream(audioFile).use { fis ->
                            player.setDataSource(fis.fd)
                        }
                    }
                )
            }.onFailure {
                // Handle content:// localUri directly if File copy wasn't used
                if (!localUriString.isNullOrBlank() && Uri.parse(localUriString).scheme == "content") {
                    startPreparedMediaPlayer(
                        messageId = message.id,
                        configureDataSource = { player ->
                            player.setDataSource(context, Uri.parse(localUriString))
                        }
                    )
                } else {
                    stopAudioPlayback()
                    _uiState.value = _uiState.value.copy(
                        audioErrorMessageId = message.id,
                        error = AppError.StorageFailure
                    )
                }
            }
        }
    }

    fun seekVoiceMessage(messageId: String, positionMs: Long) {
        val state = _uiState.value
        val player = audioPlayer ?: return
        if (state.activeAudioMessageId != messageId || state.isAudioLoading) return
        val maxDur = state.audioDurationMs.coerceAtLeast(0L)
        val clamped = positionMs.coerceIn(0L, maxDur)
        runCatching { player.seekTo(clamped.toInt()) }
        _uiState.value = _uiState.value.copy(audioPositionMs = clamped)
    }

    private fun startPreparedMediaPlayer(
        messageId: String,
        configureDataSource: (MediaPlayer) -> Unit
    ) {
        runCatching {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (am != null) {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = false
                am.mode = AudioManager.MODE_NORMAL
                requestPlaybackFocus(am)
            }
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val player = MediaPlayer().apply {
                setAudioAttributes(attrs)
                configureDataSource(this)
                setVolume(1.0f, 1.0f)
                setOnPreparedListener { mp ->
                    if (_uiState.value.activeAudioMessageId != messageId) {
                        runCatching { mp.release() }
                        return@setOnPreparedListener
                    }
                    val realDuration = mp.duration.takeIf { it > 0 }?.toLong()
                        ?: _uiState.value.audioDurationMs
                    mp.start()
                    _uiState.value = _uiState.value.copy(
                        isAudioLoading = false,
                        isAudioPlaying = true,
                        isAudioPaused = false,
                        audioDurationMs = realDuration,
                        audioPositionMs = 0L,
                        audioErrorMessageId = null
                    )
                    startAudioProgressTicker(messageId)
                }
                setOnCompletionListener {
                    stopAudioPlayback()
                }
                setOnErrorListener { _, _, _ ->
                    stopAudioPlayback()
                    _uiState.value = _uiState.value.copy(
                        audioErrorMessageId = messageId,
                        error = AppError.StorageFailure
                    )
                    true
                }
                prepareAsync()
            }
            audioPlayer = player
        }.onFailure {
            stopAudioPlayback()
            _uiState.value = _uiState.value.copy(
                audioErrorMessageId = messageId,
                error = AppError.StorageFailure
            )
        }
    }

    private fun startAudioProgressTicker(messageId: String) {
        audioProgressJob?.cancel()
        audioProgressJob = viewModelScope.launch {
            while (isActive) {
                val player = audioPlayer
                val current = _uiState.value
                if (player == null || current.activeAudioMessageId != messageId || !current.isAudioPlaying) {
                    break
                }
                val pos = runCatching { player.currentPosition.toLong() }.getOrDefault(current.audioPositionMs)
                val dur = runCatching { player.duration.toLong() }.getOrDefault(0L)
                _uiState.value = _uiState.value.copy(
                    audioPositionMs = pos.coerceAtLeast(0L),
                    audioDurationMs = if (dur > 0L) dur else current.audioDurationMs
                )
                delay(100L)
            }
        }
    }

    private fun downloadAudioToFile(url: String, target: File): Boolean {
        val tempFile = File(target.parentFile, "${target.name}.tmp")
        return runCatching {
            val req = Request.Builder().url(url).build()
            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return false
                val body = resp.body ?: return false
                body.byteStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }
            if (tempFile.exists() && tempFile.length() > 0L) {
                if (target.exists()) target.delete()
                if (!tempFile.renameTo(target)) {
                    tempFile.copyTo(target, overwrite = true)
                    tempFile.delete()
                }
                true
            } else {
                tempFile.delete()
                false
            }
        }.getOrElse {
            tempFile.delete()
            false
        }
    }

    fun downloadAndOpenFile(message: Message) {
        val media = message.media ?: return
        _uiState.value = _uiState.value.copy(downloadingMessageId = message.id, error = null)
        viewModelScope.launch {
            val urlRes = messageRepository.getSignedMediaUrl(media.id)
            urlRes.onSuccess { signedUrl ->
                val fileRes = withContext(Dispatchers.IO) {
                    runCatching {
                        val dir = File(context.cacheDir, "downloads").apply { mkdirs() }
                        val safeName = media.originalFilename.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                        val target = File(dir, safeName)
                        val req = Request.Builder().url(signedUrl).build()
                        okHttpClient.newCall(req).execute().use { resp ->
                            if (!resp.isSuccessful) error("Download HTTP ${resp.code}")
                            resp.body?.byteStream()?.use { input ->
                                FileOutputStream(target).use { output -> input.copyTo(output) }
                            } ?: error("Empty body")
                        }
                        target
                    }
                }
                _uiState.value = _uiState.value.copy(downloadingMessageId = null)
                fileRes.onSuccess { localFile ->
                    val contentUri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        localFile
                    )
                    val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(contentUri, media.mimeType)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    runCatching { context.startActivity(viewIntent) }.onFailure {
                        _uiState.value = _uiState.value.copy(error = AppError.ServerError("No app available to open ${media.originalFilename}"))
                    }
                }.onFailure {
                    _uiState.value = _uiState.value.copy(error = AppError.StorageFailure)
                }
            }.onFailure {
                _uiState.value = _uiState.value.copy(downloadingMessageId = null, error = AppError.StorageFailure)
            }
        }
    }

    fun copyMessage(message: Message) {
        val text = message.bodyText?.trim().orEmpty()
        if (text.isEmpty()) return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("Message", text))
        _uiState.value = _uiState.value.copy(infoToast = context.getString(R.string.msg_copied))
    }

    fun shareMessage(message: Message) {
        if (message.deletedForAllAtMs != null) return
        touchPrivateActivity()
        val media = message.media
        if (media == null && message.localUri.isNullOrBlank()) {
            val text = message.bodyText?.trim().orEmpty()
            if (text.isEmpty()) return
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
            val chooser = Intent.createChooser(sendIntent, context.getString(R.string.action_share)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(chooser) }.onFailure {
                _uiState.value = _uiState.value.copy(error = AppError.ServerError(context.getString(R.string.share_failed)))
            }
            return
        }

        _uiState.value = _uiState.value.copy(
            isSharingMessage = true,
            infoToast = context.getString(R.string.share_preparing)
        )
        viewModelScope.launch {
            val resolved = resolveShareableMediaFile(message)
            _uiState.value = _uiState.value.copy(isSharingMessage = false)
            resolved.onSuccess { (file, mimeType) ->
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = mimeType.ifBlank { "*/*" }
                    putExtra(Intent.EXTRA_STREAM, uri)
                    if (!message.bodyText.isNullOrBlank()) {
                        putExtra(Intent.EXTRA_TEXT, message.bodyText)
                    }
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(sendIntent, context.getString(R.string.action_share)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runCatching { context.startActivity(chooser) }.onFailure {
                    _uiState.value = _uiState.value.copy(error = AppError.ServerError(context.getString(R.string.share_failed)))
                }
            }.onFailure {
                _uiState.value = _uiState.value.copy(error = AppError.ServerError(context.getString(R.string.share_failed)))
            }
        }
    }

    fun saveImageToGallery(message: Message) {
        if (message.type != MessageType.IMAGE || message.deletedForAllAtMs != null) return
        touchPrivateActivity()
        _uiState.value = _uiState.value.copy(downloadingMessageId = message.id, error = null)
        viewModelScope.launch {
            val result = runCatching {
                val bytes = withContext(Dispatchers.IO) {
                    val localStr = message.localUri
                    if (!localStr.isNullOrBlank()) {
                        val parsed = Uri.parse(localStr)
                        if (parsed.scheme == "file" && !parsed.path.isNullOrBlank()) {
                            val f = File(parsed.path!!)
                            if (f.exists() && f.length() > 0L) return@withContext f.readBytes()
                        } else if (parsed.scheme == "content") {
                            context.contentResolver.openInputStream(parsed)?.use { it.readBytes() }?.let {
                                if (it.isNotEmpty()) return@withContext it
                            }
                        }
                    }
                    val mediaId = message.media?.id ?: error("Missing media")
                    val primaryUrl = "${baseUrl}v1/media/$mediaId/content"
                    val primaryBytes = runCatching {
                        okHttpClient.newCall(Request.Builder().url(primaryUrl).build()).execute().use { resp ->
                            if (resp.isSuccessful) resp.body?.bytes() else null
                        }
                    }.getOrNull()
                    if (primaryBytes != null && primaryBytes.isNotEmpty()) {
                        return@withContext primaryBytes
                    }
                    val signedUrl = messageRepository.getSignedMediaUrl(mediaId).getOrThrow()
                    okHttpClient.newCall(Request.Builder().url(signedUrl).build()).execute().use { resp ->
                        if (resp.isSuccessful) {
                            resp.body?.bytes() ?: error("Empty body")
                        } else {
                            error("HTTP ${resp.code}")
                        }
                    }
                }
                val filename = message.media?.originalFilename?.ifBlank { null }
                    ?: "MCP_${System.currentTimeMillis()}.jpg"
                val mime = message.media?.mimeType?.takeIf { it.startsWith("image/") }
                    ?: if (filename.lowercase().endsWith(".png")) "image/png" else "image/jpeg"
                MediaUploadHelper.saveImageToGallery(
                    context = context,
                    imageBytes = bytes,
                    preferredFilename = filename,
                    mimeType = mime
                )
            }
            _uiState.value = _uiState.value.copy(downloadingMessageId = null)
            result.onSuccess {
                _uiState.value = _uiState.value.copy(infoToast = context.getString(R.string.media_saved_to_gallery))
            }.onFailure {
                _uiState.value = _uiState.value.copy(error = AppError.ServerError(context.getString(R.string.media_save_failed)))
            }
        }
    }

    private suspend fun resolveShareableMediaFile(message: Message): Result<Pair<File, String>> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.cacheDir, "downloads").apply { mkdirs() }
            val media = message.media
            val rawName = media?.originalFilename?.takeIf { it.isNotBlank() } ?: when (message.type) {
                MessageType.IMAGE -> "image_${message.id}.jpg"
                MessageType.VIDEO -> "video_${message.id}.mp4"
                MessageType.VOICE, MessageType.AUDIO -> "voice_${message.id}.m4a"
                else -> "file_${message.id}"
            }
            val safeName = rawName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val target = File(dir, safeName)
            val mimeType = media?.mimeType ?: when (message.type) {
                MessageType.IMAGE -> "image/jpeg"
                MessageType.VIDEO -> "video/mp4"
                MessageType.VOICE, MessageType.AUDIO -> "audio/mp4"
                else -> "*/*"
            }

            if (target.exists() && target.length() > 0L) {
                return@runCatching target to mimeType
            }

            if (!message.localUri.isNullOrBlank()) {
                val parsed = Uri.parse(message.localUri)
                if (parsed.scheme == "file" && !parsed.path.isNullOrBlank()) {
                    val f = File(parsed.path!!)
                    if (f.exists() && f.length() > 0L) {
                        f.copyTo(target, overwrite = true)
                        return@runCatching target to mimeType
                    }
                } else if (parsed.scheme == "content") {
                    context.contentResolver.openInputStream(parsed)?.use { input ->
                        FileOutputStream(target).use { output -> input.copyTo(output) }
                    }
                    if (target.exists() && target.length() > 0L) {
                        return@runCatching target to mimeType
                    }
                }
            }

            val mediaId = media?.id ?: error("Missing media")
            val primaryUrl = "${baseUrl}v1/media/$mediaId/content"
            if (downloadAudioToFile(primaryUrl, target)) {
                return@runCatching target to mimeType
            }

            val signedUrl = messageRepository.getSignedMediaUrl(mediaId).getOrThrow()
            if (downloadAudioToFile(signedUrl, target)) {
                return@runCatching target to mimeType
            }
            error("Failed to download media for sharing")
        }
    }

    fun deleteMessage(messageId: String) {
        touchPrivateActivity()
        viewModelScope.launch {
            val res = if (isPrivate && privateChatRepository != null) {
                privateChatRepository.deleteOwnPrivateMessage(messageId)
            } else {
                messageRepository.deleteOwnMessage(messageId)
            }
            res.onFailure { err ->
                val appErr = (err as? AppException)?.appError ?: AppError.ServerError()
                _uiState.value = _uiState.value.copy(error = appErr)
            }
        }
    }

    // 10-Action Conversation Menu Support
    fun viewPeerProfile() {
        touchPrivateActivity()
        _uiState.value = _uiState.value.copy(peerProfileModal = PeerProfileModalState(isLoading = true))
        viewModelScope.launch {
            val res = if (isPrivate && privateChatRepository != null) {
                privateChatRepository.getPrivatePeerProfile(conversationId)
            } else {
                chatRepository.getPeerProfile(conversationId)
            }
            res.onSuccess { profile ->
                _uiState.value = _uiState.value.copy(
                    peerProfileModal = PeerProfileModalState(isLoading = false, profile = profile)
                )
            }.onFailure { err ->
                val appErr = (err as? AppException)?.appError ?: AppError.ServerError()
                _uiState.value = _uiState.value.copy(
                    peerProfileModal = PeerProfileModalState(
                        isLoading = false,
                        errorMessage = appErr.toLocalizedMessage(context)
                    )
                )
            }
        }
    }

    fun dismissPeerProfile() {
        _uiState.value = _uiState.value.copy(peerProfileModal = null)
    }

    fun setMutedUntil(mutedUntilMs: Long?) {
        touchPrivateActivity()
        viewModelScope.launch {
            val res = if (isPrivate && privateChatRepository != null) {
                privateChatRepository.setPrivateMutedUntil(conversationId, mutedUntilMs)
            } else {
                chatRepository.setMutedUntil(conversationId, mutedUntilMs)
            }
            res.onSuccess {
                _uiState.value = _uiState.value.copy(
                    infoToast = if (mutedUntilMs != null) "Notifications muted" else "Notifications unmuted"
                )
            }.onFailure { err ->
                _uiState.value = _uiState.value.copy(error = (err as? AppException)?.appError ?: AppError.ServerError())
            }
        }
    }

    fun setCustomSound(soundUri: String?, soundLabel: String?) {
        touchPrivateActivity()
        viewModelScope.launch {
            val res = if (isPrivate && privateChatRepository != null) {
                privateChatRepository.setPrivateCustomSound(conversationId, soundUri, soundLabel)
            } else {
                chatRepository.setCustomSound(conversationId, soundUri, soundLabel)
            }
            res.onSuccess {
                _uiState.value = _uiState.value.copy(
                    infoToast = soundLabel ?: "Default notification sound restored"
                )
            }.onFailure { err ->
                _uiState.value = _uiState.value.copy(error = (err as? AppException)?.appError ?: AppError.ServerError())
            }
        }
    }

    fun togglePin(currentlyPinned: Boolean) {
        touchPrivateActivity()
        viewModelScope.launch {
            val res = if (isPrivate && privateChatRepository != null) {
                privateChatRepository.setPrivatePinned(conversationId, !currentlyPinned)
            } else {
                chatRepository.setPinned(conversationId, !currentlyPinned)
            }
            res.onFailure { err ->
                _uiState.value = _uiState.value.copy(error = (err as? AppException)?.appError ?: AppError.ServerError())
            }
        }
    }

    fun toggleMarkUnread(currentlyUnread: Boolean) {
        touchPrivateActivity()
        viewModelScope.launch {
            val res = if (isPrivate && privateChatRepository != null) {
                privateChatRepository.setPrivateMarkedUnread(conversationId, !currentlyUnread)
            } else {
                chatRepository.setMarkedUnread(conversationId, !currentlyUnread)
            }
            res.onFailure { err ->
                _uiState.value = _uiState.value.copy(error = (err as? AppException)?.appError ?: AppError.ServerError())
            }
        }
    }

    fun toggleArchive(currentlyArchived: Boolean) {
        touchPrivateActivity()
        viewModelScope.launch {
            val res = if (isPrivate && privateChatRepository != null) {
                privateChatRepository.setPrivateArchived(conversationId, !currentlyArchived)
            } else {
                chatRepository.setArchived(conversationId, !currentlyArchived)
            }
            res.onSuccess {
                _uiState.value = _uiState.value.copy(
                    infoToast = if (!currentlyArchived) "Chat archived" else "Chat restored"
                )
            }.onFailure { err ->
                _uiState.value = _uiState.value.copy(error = (err as? AppException)?.appError ?: AppError.ServerError())
            }
        }
    }

    fun clearChat() {
        touchPrivateActivity()
        viewModelScope.launch {
            val res = if (isPrivate && privateChatRepository != null) {
                privateChatRepository.clearPrivateConversation(conversationId)
            } else {
                chatRepository.clearConversation(conversationId)
            }
            res.onSuccess {
                _uiState.value = _uiState.value.copy(infoToast = "Chat cleared")
            }.onFailure { err ->
                _uiState.value = _uiState.value.copy(error = (err as? AppException)?.appError ?: AppError.ServerError())
            }
        }
    }

    fun deleteChat() {
        touchPrivateActivity()
        viewModelScope.launch {
            val res = if (isPrivate && privateChatRepository != null) {
                privateChatRepository.deletePrivateConversation(conversationId)
            } else {
                chatRepository.deleteConversation(conversationId)
            }
            res.onSuccess {
                _uiState.value = _uiState.value.copy(conversationDeleted = true)
            }.onFailure { err ->
                _uiState.value = _uiState.value.copy(error = (err as? AppException)?.appError ?: AppError.ServerError())
            }
        }
    }

    fun moveToPrivateChat() {
        if (isPrivate) return
        viewModelScope.launch {
            chatRepository.moveToPrivateChat(conversationId)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        conversationDeleted = true,
                        infoToast = context.getString(R.string.chat_moved_to_private_notice)
                    )
                }
                .onFailure { err ->
                    _uiState.value = _uiState.value.copy(error = (err as? AppException)?.appError ?: AppError.ServerError())
                }
        }
    }

    fun moveToRegularChat() {
        if (!isPrivate || privateChatRepository == null) return
        touchPrivateActivity()
        viewModelScope.launch {
            privateChatRepository.moveToRegularChat(conversationId)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        conversationDeleted = true,
                        infoToast = context.getString(R.string.chat_moved_to_regular_notice)
                    )
                }
                .onFailure { err ->
                    _uiState.value = _uiState.value.copy(error = (err as? AppException)?.appError ?: AppError.ServerError())
                }
        }
    }

    fun toggleBlockPeer(currentlyBlocked: Boolean) {
        viewModelScope.launch {
            val res = if (currentlyBlocked) {
                safetyRepository.unblockUser(peerUserId)
            } else {
                safetyRepository.blockUser(peerUserId)
            }
            res.onFailure { err ->
                val appErr = (err as? AppException)?.appError ?: AppError.ServerError()
                _uiState.value = _uiState.value.copy(error = appErr)
            }
        }
    }

    fun report(messageId: String?, reason: String, details: String? = null) {
        viewModelScope.launch {
            safetyRepository.report(peerUserId, messageId, reason, details)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(infoToast = "Report submitted.")
                }
                .onFailure { err ->
                    val appErr = (err as? AppException)?.appError ?: AppError.ServerError()
                    _uiState.value = _uiState.value.copy(error = appErr)
                }
        }
    }

    fun clearError() {
        voiceRecorder.clearError()
        _uiState.value = _uiState.value.copy(error = null, infoToast = null)
    }

    private fun updateProgress(clientId: String, progress: Float) {
        _uiState.value = _uiState.value.copy(
            uploadProgressByClientId = _uiState.value.uploadProgressByClientId + (clientId to progress)
        )
    }

    private fun clearProgress(clientId: String) {
        _uiState.value = _uiState.value.copy(
            uploadProgressByClientId = _uiState.value.uploadProgressByClientId - clientId
        )
    }

    private fun stopAudioPlayback() {
        audioLoadJob?.cancel()
        audioLoadJob = null
        audioProgressJob?.cancel()
        audioProgressJob = null
        runCatching {
            if (audioPlayer?.isPlaying == true) {
                audioPlayer?.stop()
            }
            audioPlayer?.release()
        }
        audioPlayer = null
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (am != null) {
            abandonPlaybackFocus(am)
        }
        _uiState.value = _uiState.value.copy(
            activeAudioMessageId = null,
            isAudioPlaying = false,
            isAudioPaused = false,
            isAudioLoading = false,
            audioPositionMs = 0L,
            audioDurationMs = 0L
        )
    }

    private fun requestPlaybackFocus(am: AudioManager) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attrs)
                    .setOnAudioFocusChangeListener(playbackFocusListener)
                    .build()
                playbackFocusRequest = req
                am.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(playbackFocusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            }
        }
    }

    private fun abandonPlaybackFocus(am: AudioManager) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                playbackFocusRequest?.let { am.abandonAudioFocusRequest(it) }
                playbackFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(playbackFocusListener)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopAudioPlayback()
        voiceRecorder.release()
        uploadJobs.values.forEach { it.cancel() }
        uploadJobs.clear()
    }
}
