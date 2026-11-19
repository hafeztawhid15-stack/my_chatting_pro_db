package com.mychattingpro.features.calls

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mychattingpro.core.common.AppError
import com.mychattingpro.core.network.RealtimeEvent
import com.mychattingpro.core.network.RealtimeSocketManager
import com.mychattingpro.data.repository.AppException
import com.mychattingpro.domain.repository.CallRepository
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
    private val socketManager: RealtimeSocketManager
) : ViewModel() {

    val webRtcManager = WebRtcCallManager(context, socketManager)

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
    private var startedSession = false

    init {
        listenToSignaling()
    }

    fun attachVideoSinks(local: VideoSink?, remote: VideoSink?) {
        webRtcManager.attachVideoSinks(local, remote)
    }

    fun beginAfterPermissionsGranted() {
        if (startedSession) return
        startedSession = true

        viewModelScope.launch {
            val iceRes = callRepository.fetchIceServers()
            val (iceServers, turnConfigured) = iceRes.getOrElse {
                _uiState.value = _uiState.value.copy(
                    stage = WebRtcConnectionStage.FAILED,
                    error = AppError.CallFailure
                )
                return@launch
            }
            _uiState.value = _uiState.value.copy(turnConfigured = turnConfigured)

            if (!isIncoming) {
                val created = callRepository.initiateCall(conversationId, peerUserId, isVideo)
                created.onSuccess { cid ->
                    activeCallId = cid
                    _uiState.value = _uiState.value.copy(callId = cid)
                    webRtcManager.startSession(
                        callId = cid,
                        conversationId = conversationId,
                        peerUserId = peerUserId,
                        isVideo = isVideo,
                        iceServers = iceServers,
                        onStageChanged = ::onStageChanged
                    )
                }.onFailure { err ->
                    val appErr = (err as? AppException)?.appError ?: AppError.CallFailure
                    _uiState.value = _uiState.value.copy(
                        stage = WebRtcConnectionStage.FAILED,
                        error = appErr
                    )
                }
            } else {
                // Incoming call: notify caller we are ringing
                socketManager.sendSignaling(
                    "call.ringing",
                    JSONObject().apply {
                        put("callId", activeCallId)
                        put("conversationId", conversationId)
                        put("targetUserId", peerUserId)
                    }
                )
                webRtcManager.startSession(
                    callId = activeCallId,
                    conversationId = conversationId,
                    peerUserId = peerUserId,
                    isVideo = isVideo,
                    iceServers = iceServers,
                    onStageChanged = ::onStageChanged
                )
            }
        }
    }

    fun acceptIncomingCall() {
        viewModelScope.launch {
            callRepository.updateCallState(activeCallId, "accept")
            socketManager.sendSignaling(
                "call.accept",
                JSONObject().apply {
                    put("callId", activeCallId)
                    put("conversationId", conversationId)
                    put("targetUserId", peerUserId)
                }
            )
            _uiState.value = _uiState.value.copy(stage = WebRtcConnectionStage.CONNECTING)
        }
    }

    fun rejectIncomingCall(onDone: () -> Unit) {
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
        durationJob?.cancel()
        viewModelScope.launch {
            if (activeCallId.isNotBlank()) {
                callRepository.updateCallState(activeCallId, "end")
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

    private fun listenToSignaling() {
        viewModelScope.launch {
            socketManager.events.collect { event ->
                if (event !is RealtimeEvent.CallSignal) return@collect
                if (activeCallId.isNotBlank() && event.callId != activeCallId) return@collect

                when (event.type) {
                    "call.ringing" -> {
                        _uiState.value = _uiState.value.copy(stage = WebRtcConnectionStage.RINGING)
                    }
                    "call.accept" -> {
                        _uiState.value = _uiState.value.copy(stage = WebRtcConnectionStage.CONNECTING)
                        webRtcManager.createAndSendOffer()
                    }
                    "call.offer" -> {
                        val sdp = event.payload.optString("sdp")
                        if (sdp.isNotBlank()) {
                            webRtcManager.handleRemoteOfferAndSendAnswer(sdp)
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
                        durationJob?.cancel()
                        webRtcManager.endSession()
                        _uiState.value = _uiState.value.copy(stage = WebRtcConnectionStage.ENDED)
                    }
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        durationJob?.cancel()
        webRtcManager.release()
    }
}
