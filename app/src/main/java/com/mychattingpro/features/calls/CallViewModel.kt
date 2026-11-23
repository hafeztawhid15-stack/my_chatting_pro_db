package com.mychattingpro.features.calls

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mychattingpro.core.common.AppError
import com.mychattingpro.core.network.NetworkMonitor
import com.mychattingpro.core.network.RealtimeEvent
import com.mychattingpro.core.network.RealtimeSocketManager
import com.mychattingpro.data.repository.AppException
import com.mychattingpro.domain.models.IceServerConfig
import com.mychattingpro.domain.repository.CallRepository
import com.mychattingpro.domain.repository.ChatRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.webrtc.VideoSink

data class CallUiState(
    val callId: String = "",
    val isVideo: Boolean = false,
    val isIncoming: Boolean = false,
    val stage: WebRtcConnectionStage = WebRtcConnectionStage.IDLE,
    val isMuted: Boolean = false,
    val isSpeakerOn: Boolean = false,
    val isCameraOn: Boolean = true,
    val durationSeconds: Long = 0L,
    val turnConfigured: Boolean = false,
    val peerIsOnline: Boolean? = null,
    val peerLastSeenMs: Long? = null,
    val error: AppError? = null
)

class CallViewModel(
    context: Context,
    private val initialCallId: String,
    private val conversationId: String,
    private val peerUserId: String,
    private val isVideo: Boolean,
    private val isIncoming: Boolean,
    private val callRepository: CallRepository,
    private val socketManager: RealtimeSocketManager,
    private val networkMonitor: NetworkMonitor,
    private val autoAccept: Boolean = false,
    private val onStopIncomingAlert: (String) -> Unit = {},
    val webRtcManager: WebRtcCallManager = WebRtcCallManager(context, socketManager),
    private val chatRepository: ChatRepository? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        CallUiState(
            callId = initialCallId,
            isVideo = isVideo,
            isIncoming = isIncoming,
            isSpeakerOn = isVideo,
            stage = if (isIncoming) WebRtcConnectionStage.RINGING else WebRtcConnectionStage.CALLING
        )
    )
    val uiState: StateFlow<CallUiState> = _uiState.asStateFlow()

    private var activeCallId: String = initialCallId
    private var durationJob: Job? = null
    private var ringTimeoutJob: Job? = null
    private var statusPollJob: Job? = null
    private var connectingWatchdogJob: Job? = null
    private var startedSession = false
    private var cachedIceServers: List<IceServerConfig>? = null
    private var incomingHandled = false
    private var peerConnectionReady = false
    private var pendingCreateOfferOnReady = false
    private var offerSent = false
    private var pendingOfferSdp: String? = null

    init {
        listenToSignaling()
        observeNetworkForReconnecting()
        observePeerPresence()
    }

    private fun observePeerPresence() {
        val repo = chatRepository ?: return
        viewModelScope.launch {
            repo.refreshConversations()
        }
        viewModelScope.launch {
            repo.observeConversation(conversationId).collect { conv ->
                if (conv != null) {
                    _uiState.value = _uiState.value.copy(
                        peerIsOnline = conv.peerIsOnline,
                        peerLastSeenMs = conv.peerLastSeenMs
                    )
                }
            }
        }
    }

    private fun observeNetworkForReconnecting() {
        viewModelScope.launch {
            networkMonitor.isOnline.collect { online ->
                val st = _uiState.value.stage
                if (!online && (st == WebRtcConnectionStage.CONNECTED || st == WebRtcConnectionStage.CONNECTING)) {
                    _uiState.value = _uiState.value.copy(stage = WebRtcConnectionStage.RECONNECTING)
                }
            }
        }
    }

    fun attachVideoSinks(local: VideoSink?, remote: VideoSink?) {
        webRtcManager.attachVideoSinks(local, remote)
    }

    fun beginAfterPermissionsGranted() {
        if (startedSession) return
        startedSession = true

        viewModelScope.launch {
            socketManager.start()
            val iceRes = callRepository.fetchIceServers()
            val (iceServers, turnConfigured) = iceRes.getOrElse {
                listOf(
                    IceServerConfig(urls = listOf("stun:stun.cloudflare.com:3478", "stun:stun.l.google.com:19302"))
                ) to false
            }
            cachedIceServers = iceServers
            _uiState.value = _uiState.value.copy(turnConfigured = turnConfigured)

            if (!isIncoming) {
                val created = callRepository.initiateCall(conversationId, peerUserId, isVideo)
                created.onSuccess { cid ->
                    activeCallId = cid
                    _uiState.value = _uiState.value.copy(callId = cid)
                    val ok = webRtcManager.startSession(
                        callId = cid,
                        conversationId = conversationId,
                        peerUserId = peerUserId,
                        isVideo = isVideo,
                        iceServers = iceServers,
                        onStageChanged = ::onStageChanged
                    )
                    if (!ok) {
                        runCatching { callRepository.updateCallState(cid, "end") }
                        _uiState.value = _uiState.value.copy(
                            stage = WebRtcConnectionStage.FAILED,
                            error = AppError.CallFailure
                        )
                        return@onSuccess
                    }
                    peerConnectionReady = true
                    if (pendingCreateOfferOnReady) {
                        pendingCreateOfferOnReady = false
                        triggerCreateOfferOnce()
                    }
                    startOutgoingPollingAndTimeout(cid)
                }.onFailure { err ->
                    val appErr = (err as? AppException)?.appError ?: AppError.CallFailure
                    _uiState.value = _uiState.value.copy(
                        stage = WebRtcConnectionStage.FAILED,
                        error = appErr
                    )
                }
            } else {
                socketManager.sendSignaling(
                    "call.ringing",
                    JSONObject().apply {
                        put("callId", activeCallId)
                        put("conversationId", conversationId)
                        put("targetUserId", peerUserId)
                    }
                )
                if (autoAccept && !incomingHandled) {
                    acceptIncomingCall()
                }
            }
        }
    }

    private fun startOutgoingPollingAndTimeout(callId: String) {
        statusPollJob?.cancel()
        statusPollJob = viewModelScope.launch {
            while (isActive) {
                delay(2_500L)
                val currentStage = _uiState.value.stage
                if (currentStage != WebRtcConnectionStage.CALLING &&
                    currentStage != WebRtcConnectionStage.RINGING
                ) {
                    break
                }
                val remoteStatus = callRepository.getCallStatus(callId).getOrNull()
                when (remoteStatus) {
                    "accepted" -> {
                        handlePeerAccepted()
                        break
                    }
                    "rejected", "ended", "missed" -> {
                        ringTimeoutJob?.cancel()
                        onStopIncomingAlert(callId)
                        durationJob?.cancel()
                        webRtcManager.endSession()
                        _uiState.value = _uiState.value.copy(stage = WebRtcConnectionStage.ENDED)
                        break
                    }
                }
            }
        }

        ringTimeoutJob?.cancel()
        ringTimeoutJob = viewModelScope.launch {
            delay(45_000L)
            val stage = _uiState.value.stage
            if (stage == WebRtcConnectionStage.CALLING || stage == WebRtcConnectionStage.RINGING) {
                statusPollJob?.cancel()
                runCatching { callRepository.updateCallState(callId, "missed") }
                socketManager.sendSignaling(
                    "call.end",
                    JSONObject().apply {
                        put("callId", callId)
                        put("conversationId", conversationId)
                        put("targetUserId", peerUserId)
                    }
                )
                webRtcManager.endSession()
                _uiState.value = _uiState.value.copy(stage = WebRtcConnectionStage.ENDED)
            }
        }
    }

    fun acceptIncomingCall() {
        if (incomingHandled) return
        incomingHandled = true
        onStopIncomingAlert(activeCallId)
        _uiState.value = _uiState.value.copy(stage = WebRtcConnectionStage.CONNECTING)

        viewModelScope.launch {
            socketManager.start()
            val iceServers = cachedIceServers ?: callRepository.fetchIceServers()
                .getOrNull()
                ?.first
                .orEmpty()
                .ifEmpty {
                    listOf(
                        IceServerConfig(urls = listOf("stun:stun.cloudflare.com:3478", "stun:stun.l.google.com:19302"))
                    )
                }
                .also { cachedIceServers = it }

            val ok = webRtcManager.startSession(
                callId = activeCallId,
                conversationId = conversationId,
                peerUserId = peerUserId,
                isVideo = isVideo,
                iceServers = iceServers,
                onStageChanged = ::onStageChanged
            )
            if (!ok) {
                runCatching { callRepository.updateCallState(activeCallId, "end") }
                _uiState.value = _uiState.value.copy(
                    stage = WebRtcConnectionStage.FAILED,
                    error = AppError.CallFailure
                )
                return@launch
            }
            peerConnectionReady = true
            pendingOfferSdp?.let { offer ->
                pendingOfferSdp = null
                webRtcManager.handleRemoteOfferAndSendAnswer(offer)
            }
            callRepository.updateCallState(activeCallId, "accept")
            socketManager.sendSignaling(
                "call.accept",
                JSONObject().apply {
                    put("callId", activeCallId)
                    put("conversationId", conversationId)
                    put("targetUserId", peerUserId)
                }
            )
        }
    }

    fun rejectIncomingCall(onDone: () -> Unit) {
        if (incomingHandled) return
        incomingHandled = true
        onStopIncomingAlert(activeCallId)

        viewModelScope.launch {
            callRepository.updateCallState(activeCallId, "reject")
            socketManager.sendSignaling(
                "call.reject",
                JSONObject().apply {
                    put("callId", activeCallId)
                    put("conversationId", conversationId)
                    put("targetUserId", peerUserId)
                }
            )
            webRtcManager.endSession()
            _uiState.value = _uiState.value.copy(stage = WebRtcConnectionStage.ENDED)
            onDone()
        }
    }

    fun endCall(onDone: () -> Unit) {
        incomingHandled = true
        statusPollJob?.cancel()
        ringTimeoutJob?.cancel()
        connectingWatchdogJob?.cancel()
        onStopIncomingAlert(activeCallId)
        durationJob?.cancel()
        val wasNeverConnected = _uiState.value.stage == WebRtcConnectionStage.CALLING ||
            _uiState.value.stage == WebRtcConnectionStage.RINGING
        viewModelScope.launch {
            if (activeCallId.isNotBlank()) {
                val action = if (!isIncoming && wasNeverConnected) "missed" else "end"
                callRepository.updateCallState(activeCallId, action)
                socketManager.sendSignaling(
                    "call.end",
                    JSONObject().apply {
                        put("callId", activeCallId)
                        put("conversationId", conversationId)
                        put("targetUserId", peerUserId)
                    }
                )
            }
            webRtcManager.endSession()
            _uiState.value = _uiState.value.copy(stage = WebRtcConnectionStage.ENDED)
            onDone()
        }
    }

    fun toggleMute() {
        val nextMuted = !_uiState.value.isMuted
        webRtcManager.setMicEnabled(!nextMuted)
        _uiState.value = _uiState.value.copy(isMuted = nextMuted)
    }

    fun toggleSpeaker() {
        val nextSpeaker = !_uiState.value.isSpeakerOn
        webRtcManager.configureAudioRouting(nextSpeaker)
        _uiState.value = _uiState.value.copy(isSpeakerOn = nextSpeaker)
    }

    fun toggleCamera() {
        val nextCam = !_uiState.value.isCameraOn
        webRtcManager.setCameraEnabled(nextCam)
        _uiState.value = _uiState.value.copy(isCameraOn = nextCam)
    }

    fun switchCamera() {
        webRtcManager.switchCamera()
    }

    private fun onStageChanged(stage: WebRtcConnectionStage) {
        if (stage == WebRtcConnectionStage.CONNECTING ||
            stage == WebRtcConnectionStage.CONNECTED ||
            stage == WebRtcConnectionStage.ENDED ||
            stage == WebRtcConnectionStage.FAILED
        ) {
            statusPollJob?.cancel()
            ringTimeoutJob?.cancel()
            onStopIncomingAlert(activeCallId)
        }
        if (stage == WebRtcConnectionStage.CONNECTING) {
            startConnectingWatchdog()
        } else {
            connectingWatchdogJob?.cancel()
        }
        _uiState.value = _uiState.value.copy(stage = stage)
        if (stage == WebRtcConnectionStage.CONNECTED && durationJob == null) {
            durationJob = viewModelScope.launch {
                while (isActive) {
                    delay(1000)
                    _uiState.value = _uiState.value.copy(
                        durationSeconds = _uiState.value.durationSeconds + 1
                    )
                }
            }
        }
    }

    private fun startConnectingWatchdog() {
        connectingWatchdogJob?.cancel()
        connectingWatchdogJob = viewModelScope.launch {
            delay(30_000L)
            if (_uiState.value.stage == WebRtcConnectionStage.CONNECTING) {
                webRtcManager.endSession()
                _uiState.value = _uiState.value.copy(
                    stage = WebRtcConnectionStage.FAILED,
                    error = AppError.CallFailure
                )
            }
        }
    }

    private fun listenToSignaling() {
        viewModelScope.launch {
            socketManager.events.collect { event ->
                when (event) {
                    is RealtimeEvent.PresenceChanged -> {
                        if (event.userId == peerUserId) {
                            _uiState.value = _uiState.value.copy(
                                peerIsOnline = event.isOnline,
                                peerLastSeenMs = event.lastSeenMs ?: _uiState.value.peerLastSeenMs
                            )
                        }
                    }
                    is RealtimeEvent.CallMissed -> {
                        if (activeCallId.isNotBlank() && event.callId == activeCallId) {
                            ringTimeoutJob?.cancel()
                            onStopIncomingAlert(activeCallId)
                            durationJob?.cancel()
                            webRtcManager.endSession()
                            _uiState.value = _uiState.value.copy(stage = WebRtcConnectionStage.ENDED)
                        }
                    }
                    is RealtimeEvent.CallStateChanged -> {
                        if (activeCallId.isNotBlank() && event.callId == activeCallId) {
                            when (event.status) {
                                "accepted" -> if (!isIncoming) {
                                    handlePeerAccepted()
                                }
                                "rejected", "ended", "missed" -> {
                                    ringTimeoutJob?.cancel()
                                    onStopIncomingAlert(activeCallId)
                                    durationJob?.cancel()
                                    webRtcManager.endSession()
                                    _uiState.value = _uiState.value.copy(stage = WebRtcConnectionStage.ENDED)
                                }
                            }
                        }
                    }
                    is RealtimeEvent.CallSignal -> {
                        if (activeCallId.isNotBlank() && event.callId != activeCallId) return@collect
                        when (event.type) {
                            "call.ringing" -> {
                                if (_uiState.value.stage == WebRtcConnectionStage.CALLING) {
                                    _uiState.value = _uiState.value.copy(stage = WebRtcConnectionStage.RINGING)
                                }
                            }
                            "call.accept" -> {
                                handlePeerAccepted()
                            }
                            "call.offer" -> {
                                val sdp = event.payload.optString("sdp")
                                if (sdp.isNotBlank()) {
                                    if (peerConnectionReady) {
                                        webRtcManager.handleRemoteOfferAndSendAnswer(sdp)
                                    } else {
                                        pendingOfferSdp = sdp
                                    }
                                }
                            }
                            "call.answer" -> {
                                val sdp = event.payload.optString("sdp")
                                if (sdp.isNotBlank()) {
                                    webRtcManager.handleRemoteAnswer(sdp)
                                }
                            }
                            "call.ice" -> {
                                val sdpMid = event.payload.optString("sdpMid")
                                val mLineIdx = event.payload.optInt("sdpMLineIndex", 0)
                                val cand = event.payload.optString("candidate")
                                if (cand.isNotBlank()) {
                                    webRtcManager.handleRemoteIceCandidate(sdpMid, mLineIdx, cand)
                                }
                            }
                            "call.reject", "call.end" -> {
                                ringTimeoutJob?.cancel()
                                incomingHandled = true
                                onStopIncomingAlert(activeCallId)
                                durationJob?.cancel()
                                webRtcManager.endSession()
                                _uiState.value = _uiState.value.copy(stage = WebRtcConnectionStage.ENDED)
                            }
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun handlePeerAccepted() {
        statusPollJob?.cancel()
        ringTimeoutJob?.cancel()
        onStopIncomingAlert(activeCallId)
        val stage = _uiState.value.stage
        if (stage == WebRtcConnectionStage.CALLING || stage == WebRtcConnectionStage.RINGING) {
            _uiState.value = _uiState.value.copy(stage = WebRtcConnectionStage.CONNECTING)
            startConnectingWatchdog()
        }
        if (peerConnectionReady) {
            triggerCreateOfferOnce()
        } else {
            pendingCreateOfferOnReady = true
        }
    }

    private fun triggerCreateOfferOnce() {
        if (offerSent) return
        offerSent = true
        webRtcManager.createAndSendOffer()
    }

    override fun onCleared() {
        super.onCleared()
        statusPollJob?.cancel()
        ringTimeoutJob?.cancel()
        connectingWatchdogJob?.cancel()
        onStopIncomingAlert(activeCallId)
        durationJob?.cancel()
        webRtcManager.release()
    }
}
