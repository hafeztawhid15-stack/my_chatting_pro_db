package com.mychattingpro.features.messages

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mychattingpro.core.common.AppError
import com.mychattingpro.core.datastore.AppPreferencesDataStore
import com.mychattingpro.core.localization.AppLanguage
import com.mychattingpro.core.network.NetworkMonitor
import com.mychattingpro.core.network.RealtimeEvent
import com.mychattingpro.core.network.RealtimeSocketManager
import com.mychattingpro.core.network.SocketConnectionState
import com.mychattingpro.core.security.EncryptedTokenStore
import com.mychattingpro.data.repository.AppException
import com.mychattingpro.domain.models.Conversation
import com.mychattingpro.domain.models.Message
import com.mychattingpro.domain.models.MessageType
import com.mychattingpro.domain.repository.ChatRepository
import com.mychattingpro.domain.repository.MessageRepository
import com.mychattingpro.domain.repository.SafetyRepository
import com.mychattingpro.features.media.VoiceRecorderManager
import com.mychattingpro.features.media.VoiceRecordingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class ChatDetailUiState(
    val composerText: String = "",
    val isLoadingInitial: Boolean = true,
    val isLoadingOlder: Boolean = false,
    val hasMoreOlder: Boolean = true,
    val isPeerTyping: Boolean = false,
    val uploadProgressByClientId: Map<String, Float> = emptyMap(),
    val activeAudioMessageId: String? = null,
    val isAudioPlaying: Boolean = false,
    val pendingMediaPreviewUri: Uri? = null,
    val pendingMediaIsVideo: Boolean = false,
    val downloadingMessageId: String? = null,
    val error: AppError? = null,
    val infoToast: String? = null
)

class ChatDetailViewModel(
    private val context: Context,
    val conversationId: String,
    val peerUserId: String,
    chatRepository: ChatRepository,
    private val messageRepository: MessageRepository,
    private val safetyRepository: SafetyRepository,
    private val tokenStore: EncryptedTokenStore,
    prefsStore: AppPreferencesDataStore,
    private val socketManager: RealtimeSocketManager,
    private val okHttpClient: OkHttpClient,
    networkMonitor: NetworkMonitor
) : ViewModel() {

    val voiceRecorder = VoiceRecorderManager(context)
    val voiceState: StateFlow<VoiceRecordingState> = voiceRecorder.state

    val conversation: StateFlow<Conversation?> = chatRepository.observeConversation(conversationId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val messages: StateFlow<List<Message>> = messageRepository.observeMessages(conversationId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
    private var typingTimeoutJob: Job? = null
    private var audioPlayer: MediaPlayer? = null

    init {
        viewModelScope.launch {
            myUserId = tokenStore.getSession()?.userId
            val res = messageRepository.loadLatestMessages(conversationId)
            res.onSuccess { hasMore ->
                _uiState.value = _uiState.value.copy(isLoadingInitial = false, hasMoreOlder = hasMore)
            }.onFailure { err ->
                val appErr = (err as? AppException)?.appError ?: AppError.ServerError()
                _uiState.value = _uiState.value.copy(isLoadingInitial = false, error = appErr)
            }
        }

        // Mark incoming messages read whenever messages update
        viewModelScope.launch {
            messages.collect { list ->
                val mine = myUserId ?: return@collect
                val unreadIds = list
                    .filter { it.senderId != mine && it.readAtMs == null && !it.id.startsWith("pending_") }
                    .map { it.id }
                if (unreadIds.isNotEmpty()) {
                    messageRepository.markReceipts(conversationId, unreadIds, read = true)
                }
            }
        }

        // Listen for peer typing events
        viewModelScope.launch {
            socketManager.events.collect { ev ->
                if (ev is RealtimeEvent.TypingChanged &&
                    ev.conversationId == conversationId &&
                    ev.fromUserId == peerUserId
                ) {
                    _uiState.value = _uiState.value.copy(isPeerTyping = ev.isTyping)
                }
            }
        }
    }

    fun updateComposerText(text: String) {
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

    fun sendTextMessage() {
        val text = _uiState.value.composerText.trim()
        if (text.isEmpty()) return
        _uiState.value = _uiState.value.copy(composerText = "", error = null)
        socketManager.sendTyping(conversationId, peerUserId, false)

        viewModelScope.launch {
            val res = messageRepository.sendTextMessage(conversationId, text)
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
        val cid = UUID.randomUUID().toString()
        val job = viewModelScope.launch {
            updateProgress(cid, 0.05f)
            val res = messageRepository.sendMediaMessage(
                conversationId = conversationId,
                uri = uri,
                messageType = messageType,
                category = category,
                caption = caption,
                durationMs = durationMs,
                existingClientMessageId = cid,
                onProgress = { p -> updateProgress(cid, p) }
            )
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
        val cid = message.clientMessageId
        val job = viewModelScope.launch {
            if (message.type != MessageType.TEXT) updateProgress(cid, 0.05f)
            val res = messageRepository.retryFailedMessage(message) { p ->
                updateProgress(cid, p)
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

        _uiState.value = state.copy(isLoadingOlder = true)
        viewModelScope.launch {
            val res = messageRepository.loadOlderMessages(conversationId, oldest.createdAtMs, oldest.id)
            res.onSuccess { hasMore ->
                _uiState.value = _uiState.value.copy(isLoadingOlder = false, hasMoreOlder = hasMore)
            }.onFailure {
                _uiState.value = _uiState.value.copy(isLoadingOlder = false)
            }
        }
    }

    fun sendRecordedVoiceNote() {
        val result = voiceRecorder.stopRecording() ?: return
        val (file, durationMs) = result
        voiceRecorder.cancelRecording()
        sendMediaUri(
            uri = Uri.fromFile(file),
            messageType = MessageType.VOICE,
            category = "audio",
            durationMs = durationMs
        )
    }

    fun toggleVoiceMessagePlayback(message: Message) {
        val mediaId = message.media?.id ?: return
        if (_uiState.value.activeAudioMessageId == message.id && _uiState.value.isAudioPlaying) {
            stopAudioPlayback()
            return
        }

        stopAudioPlayback()
        _uiState.value = _uiState.value.copy(activeAudioMessageId = message.id, isAudioPlaying = true)
        viewModelScope.launch {
            val urlRes = messageRepository.getSignedMediaUrl(mediaId)
            urlRes.onSuccess { signedUrl ->
                runCatching {
                    val player = MediaPlayer().apply {
                        setDataSource(signedUrl)
                        setOnPreparedListener { it.start() }
                        setOnCompletionListener {
                            _uiState.value = _uiState.value.copy(activeAudioMessageId = null, isAudioPlaying = false)
                        }
                        setOnErrorListener { _, _, _ ->
                            _uiState.value = _uiState.value.copy(activeAudioMessageId = null, isAudioPlaying = false, error = AppError.StorageFailure)
                            true
                        }
                        prepareAsync()
                    }
                    audioPlayer = player
                }.onFailure {
                    stopAudioPlayback()
                }
            }.onFailure {
                stopAudioPlayback()
                _uiState.value = _uiState.value.copy(error = AppError.StorageFailure)
            }
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

    fun deleteMessage(messageId: String) {
        viewModelScope.launch {
            messageRepository.deleteOwnMessage(messageId).onFailure { err ->
                val appErr = (err as? AppException)?.appError ?: AppError.ServerError()
                _uiState.value = _uiState.value.copy(error = appErr)
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

    fun report(messageId: String?, reason: String) {
        viewModelScope.launch {
            safetyRepository.report(peerUserId, messageId, reason, null)
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
        runCatching {
            audioPlayer?.stop()
            audioPlayer?.release()
        }
        audioPlayer = null
        _uiState.value = _uiState.value.copy(activeAudioMessageId = null, isAudioPlaying = false)
    }

    override fun onCleared() {
        super.onCleared()
        stopAudioPlayback()
        voiceRecorder.release()
        uploadJobs.values.forEach { it.cancel() }
        uploadJobs.clear()
    }
}
