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
    private var signalPollJob: Job? = null
    private var connectingWatchdogJob: Job? = null
    private var offerRetryJob: Job? = null
    private var acceptRetryJob: Job? = null
    private var startedSession = false
    private var cachedIceServers: List<IceServerConfig>? = null
    private var incomingHandled = false
    private var peerConnectionReady = false
    private var pendingCreateOfferOnReady = false
    private var offerSent = false
    private var hasRemoteOffer = false
    private var hasRemoteAnswer = false
    private var pendingOfferSdp: String? = null
    private var lastSignalTimestampMs: Long = 0L
    private var iceRestartOnFailAttempted = false
    private var iceFallbackRelayCount = 0
    private var lastPeerAcceptedHandledAtMs = 0L

    init {
        webRtcManager.setSignalEmitter(::emitCallSignal)
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
                    if (conv.isBlockedByMe || conv.hasBlockedMe) {
                        val st = _uiState.value.stage
                        if (st != WebRtcConnectionStage.ENDED && st != WebRtcConnectionStage.FAILED) {
                            cancelAllTimers()
                            incomingHandled = true
                            onStopIncomingAlert(activeCallId)
                            webRtcManager.endSession()
                            _uiState.value = _uiState.value.copy(
                                stage = WebRtcConnectionStage.FAILED,
                                error = AppError.UserBlocked
                            )
                            return@collect
                        }
                    }
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

    private fun emitCallSignal(type: String, payload: JSONObject) {
        socketManager.sendSignaling(type, payload)
        val cid = payload.optString("callId").ifBlank { activeCallId }
        if (cid.isBlank()) return
        val shouldRelayHttp = type != "call.ice" ||
            !socketManager.isConnected() ||
            iceFallbackRelayCount < 8
        if (!shouldRelayHttp) return
        if (type == "call.ice") {
            iceFallbackRelayCount++
        }
        viewModelScope.launch {
            runCatching {
                callRepository.sendCallSignal(
                    callId = cid,
                    type = type,
                    conversationId = conversationId,
                    targetUserId = peerUserId,
                    payload = payload
                )
            }
        }
    }

    private fun startSignalPolling(callId: String) {
        if (callId.isBlank() || signalPollJob?.isActive == true) return
        signalPollJob = viewModelScope.launch {
            for (attempt in 0 until 35) {
                val st = _uiState.value.stage
                if (st != WebRtcConnectionStage.CALLING &&
                    st != WebRtcConnectionStage.RINGING &&
                    st != WebRtcConnectionStage.CONNECTING &&
                    st != WebRtcConnectionStage.RECONNECTING
                ) {
                    break
                }
                val signals = runCatching {
                    callRepository.pullCallSignals(callId, lastSignalTimestampMs).getOrNull().orEmpty()
                }.getOrDefault(emptyList())
                for (sig in signals) {
                    val ts = sig.optLong("timestampMs", 0L)
                    if (ts > lastSignalTimestampMs) {
                        lastSignalTimestampMs = ts
                    }
                    socketManager.ingestRealtimeEnvelope(sig)
                }
                delay(1_500L)
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
                defaultStunServers() to false
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
                    startSignalPolling(cid)
                }.onFailure { err ->
                    val appErr = (err as? AppException)?.appError ?: AppError.CallFailure
                    _uiState.value = _uiState.value.copy(
                        stage = WebRtcConnectionStage.FAILED,
                        error = appErr
                    )
                }
            } else {
                emitCallSignal(
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
                delay(2_000L)
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
                        cancelAllTimers()
                        onStopIncomingAlert(callId)
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
                signalPollJob?.cancel()
                runCatching { callRepository.updateCallState(callId, "missed") }
                emitCallSignal(
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

    fun acceptFromExternalTrigger() {
        acceptIncomingCall()
    }

    fun acceptIncomingCall() {
        if (incomingHandled) return
        incomingHandled = true
        onStopIncomingAlert(activeCallId)
        _uiState.value = _uiState.value.copy(stage = WebRtcConnectionStage.CONNECTING)
        startConnectingWatchdog()

        viewModelScope.launch {
            socketManager.start()
            val fetched = callRepository.fetchIceServers().getOrNull()
            if (fetched != null) {
                cachedIceServers = fetched.first
                _uiState.value = _uiState.value.copy(turnConfigured = fetched.second)
            }
            val iceServers = cachedIceServers ?: defaultStunServers().also { cachedIceServers = it }

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
                cancelAllTimers()
                _uiState.value = _uiState.value.copy(
                    stage = WebRtcConnectionStage.FAILED,
                    error = AppError.CallFailure
                )
                return@launch
            }
            peerConnectionReady = true
            startSignalPolling(activeCallId)
            pendingOfferSdp?.let { offer ->
                pendingOfferSdp = null
                hasRemoteOffer = true
                webRtcManager.handleRemoteOfferAndSendAnswer(offer)
            }
            callRepository.updateCallState(activeCallId, "accept")
            sendAcceptSignal()
            startAcceptRetryIfNoOffer()
        }
    }

    private fun sendAcceptSignal() {
        emitCallSignal(
            "call.accept",
            JSONObject().apply {
                put("callId", activeCallId)
                put("conversationId", conversationId)
                put("targetUserId", peerUserId)
            }
        )
    }

    private fun startAcceptRetryIfNoOffer() {
        acceptRetryJob?.cancel()
        acceptRetryJob = viewModelScope.launch {
            for (waitMs in listOf(2_000L, 3_000L, 4_000L)) {
                delay(waitMs)
                if (hasRemoteOffer || _uiState.value.stage != WebRtcConnectionStage.CONNECTING) {
                    break
                }
                sendAcceptSignal()
            }
        }
    }

    fun rejectIncomingCall(onDone: () -> Unit) {
        if (incomingHandled) return
        incomingHandled = true
        cancelAllTimers()
        onStopIncomingAlert(activeCallId)

        viewModelScope.launch {
            callRepository.updateCallState(activeCallId, "reject")
            emitCallSignal(
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
        cancelAllTimers()
        onStopIncomingAlert(activeCallId)
        val wasNeverConnected = _uiState.value.stage == WebRtcConnectionStage.CALLING ||
            _uiState.value.stage == WebRtcConnectionStage.RINGING
        viewModelScope.launch {
            if (activeCallId.isNotBlank()) {
                val action = if (!isIncoming && wasNeverConnected) "missed" else "end"
                callRepository.updateCallState(activeCallId, action)
                emitCallSignal(
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
        val current = _uiState.value.stage
        // Prevent late WebRTC connection state callbacks from regressing an active CONNECTED call back to CONNECTING
        if (stage == WebRtcConnectionStage.CONNECTING &&
            (current == WebRtcConnectionStage.CONNECTED || current == WebRtcConnectionStage.ENDED || current == WebRtcConnectionStage.FAILED)
        ) {
            return
        }
        if (current == WebRtcConnectionStage.ENDED && stage != WebRtcConnectionStage.ENDED) {
            return
        }

        if (stage == WebRtcConnectionStage.FAILED &&
            peerConnectionReady &&
            !iceRestartOnFailAttempted &&
            (hasRemoteOffer || hasRemoteAnswer || !isIncoming)
        ) {
            iceRestartOnFailAttempted = true
            _uiState.value = _uiState.value.copy(stage = WebRtcConnectionStage.CONNECTING)
            startConnectingWatchdog()
            if (!isIncoming) {
                webRtcManager.createAndSendOffer(iceRestart = true)
            } else {
                sendAcceptSignal()
            }
            return
        }

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
        if (stage == WebRtcConnectionStage.CONNECTED ||
            stage == WebRtcConnectionStage.ENDED ||
            stage == WebRtcConnectionStage.FAILED
        ) {
            signalPollJob?.cancel()
            offerRetryJob?.cancel()
            acceptRetryJob?.cancel()
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
        if (connectingWatchdogJob?.isActive == true) return
        connectingWatchdogJob = viewModelScope.launch {
            delay(12_000L)
            if (_uiState.value.stage == WebRtcConnectionStage.CONNECTING) {
                if (!isIncoming && peerConnectionReady) {
                    webRtcManager.createAndSendOffer(iceRestart = true)
                } else if (isIncoming && !hasRemoteOffer) {
                    sendAcceptSignal()
                }
            }
            delay(23_000L)
            if (_uiState.value.stage == WebRtcConnectionStage.CONNECTING) {
                signalPollJob?.cancel()
                if (activeCallId.isNotBlank()) {
                    runCatching { callRepository.updateCallState(activeCallId, "end") }
                    emitCallSignal(
                        "call.end",
                        JSONObject().apply {
                            put("callId", activeCallId)
                            put("conversationId", conversationId)
                            put("targetUserId", peerUserId)
                        }
                    )
                }
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
                            cancelAllTimers()
                            onStopIncomingAlert(activeCallId)
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
                                    cancelAllTimers()
                                    onStopIncomingAlert(activeCallId)
                                    webRtcManager.endSession()
                                    _uiState.value = _uiState.value.copy(stage = WebRtcConnectionStage.ENDED)
                                }
                            }
                        }
                    }
                    is RealtimeEvent.CallSignal -> {
                        if (activeCallId.isBlank() || event.callId != activeCallId) return@collect
                        when (event.type) {
                            "call.ringing" -> {
                                if (_uiState.value.stage == WebRtcConnectionStage.CALLING) {
                                    _uiState.value = _uiState.value.copy(stage = WebRtcConnectionStage.RINGING)
                                }
                            }
                            "call.accept" -> {
                                if (!isIncoming) {
                                    handlePeerAccepted()
                                }
                            }
                            "call.offer" -> {
                                val sdp = event.payload.optString("sdp")
                                if (sdp.isNotBlank()) {
                                    acceptRetryJob?.cancel()
                                    if (peerConnectionReady) {
                                        hasRemoteOffer = true
                                        webRtcManager.handleRemoteOfferAndSendAnswer(sdp)
                                    } else {
                                        pendingOfferSdp = sdp
                                    }
                                }
                            }
                            "call.answer" -> {
                                val sdp = event.payload.optString("sdp")
                                if (sdp.isNotBlank()) {
                                    hasRemoteAnswer = true
                                    offerRetryJob?.cancel()
                                    webRtcManager.handleRemoteAnswer(sdp)
                                }
                            }
                            "call.ice" -> {
                                val sdpMid = event.payload.opt("sdpMid")
                                    ?.takeIf { it !== JSONObject.NULL }
                                    ?.toString()
                                    ?: ""
                                val mLineIdx = event.payload.optInt("sdpMLineIndex", 0)
                                val cand = event.payload.optString("candidate")
                                if (cand.isNotBlank()) {
                                    webRtcManager.handleRemoteIceCandidate(sdpMid, mLineIdx, cand)
                                }
                            }
                            "call.reject", "call.end" -> {
                                cancelAllTimers()
                                incomingHandled = true
                                onStopIncomingAlert(activeCallId)
                                webRtcManager.endSession()
                                _uiState.value = _uiState.value.copy(stage = WebRtcConnectionStage.ENDED)
                            }
                        }
                    }
                    is RealtimeEvent.BlockStatusChanged -> {
                        if ((event.actorUserId == peerUserId || event.targetUserId == peerUserId) && event.blockedBetween) {
                            cancelAllTimers()
                            incomingHandled = true
                            onStopIncomingAlert(activeCallId)
                            webRtcManager.endSession()
                            _uiState.value = _uiState.value.copy(
                                stage = WebRtcConnectionStage.FAILED,
                                error = AppError.UserBlocked
                            )
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
        val now = System.currentTimeMillis()
        if (peerConnectionReady) {
            if (!offerSent) {
                lastPeerAcceptedHandledAtMs = now
                triggerCreateOfferOnce()
            } else if (!hasRemoteAnswer && (now - lastPeerAcceptedHandledAtMs) > 1_200L) {
                lastPeerAcceptedHandledAtMs = now
                webRtcManager.createAndSendOffer()
            }
        } else {
            pendingCreateOfferOnReady = true
        }
    }

    private fun triggerCreateOfferOnce() {
        if (offerSent) return
        offerSent = true
        webRtcManager.createAndSendOffer()
        startOfferRetryIfNoAnswer()
    }

    private fun startOfferRetryIfNoAnswer() {
        offerRetryJob?.cancel()
        offerRetryJob = viewModelScope.launch {
            for (waitMs in listOf(2_500L, 3_500L, 4_500L)) {
                delay(waitMs)
                if (hasRemoteAnswer || _uiState.value.stage != WebRtcConnectionStage.CONNECTING) {
                    break
                }
                if (peerConnectionReady) {
                    webRtcManager.createAndSendOffer()
                }
            }
        }
    }

    private fun cancelAllTimers() {
        statusPollJob?.cancel()
        signalPollJob?.cancel()
        ringTimeoutJob?.cancel()
        connectingWatchdogJob?.cancel()
        offerRetryJob?.cancel()
        acceptRetryJob?.cancel()
        durationJob?.cancel()
    }

    private fun defaultStunServers(): List<IceServerConfig> = listOf(
        IceServerConfig(
            urls = listOf(
                "stun:stun.cloudflare.com:3478",
                "stun:stun.l.google.com:19302",
                "stun:stun1.l.google.com:19302",
                "stun:stun2.l.google.com:19302"
            )
        )
    )

    override fun onCleared() {
        super.onCleared()
        cancelAllTimers()
        onStopIncomingAlert(activeCallId)
        webRtcManager.release()
    }
}
