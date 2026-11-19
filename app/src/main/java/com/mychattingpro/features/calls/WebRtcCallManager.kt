package com.mychattingpro.features.calls

import android.content.Context
import android.media.AudioManager
import com.mychattingpro.core.network.RealtimeSocketManager
import com.mychattingpro.core.security.SafeLogger
import com.mychattingpro.domain.models.IceServerConfig
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule

enum class WebRtcConnectionStage {
    IDLE, CALLING, RINGING, CONNECTING, CONNECTED, RECONNECTING, ENDED, FAILED
}

/**
 * Genuine WebRTC Audio & Video Call Engine powered by Stream WebRTC Android (`org.webrtc.*`)
 * and Cloudflare Durable Objects WebSocket signaling.
 */
class WebRtcCallManager(
    private val context: Context,
    private val socketManager: RealtimeSocketManager
) {
    val eglBase: EglBase = EglBase.create()

    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    private var localSink: VideoSink? = null
    private var remoteSink: VideoSink? = null

    private var currentCallId: String = ""
    private var currentConversationId: String = ""
    private var currentPeerUserId: String = ""
    private var isVideoCall: Boolean = false

    private val pendingIceCandidates = mutableListOf<IceCandidate>()
    private var hasRemoteDescription = false

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun attachVideoSinks(local: VideoSink?, remote: VideoSink?) {
        localSink = local
        remoteSink = remote
        local?.let { localVideoTrack?.addSink(it) }
    }

    fun startSession(
        callId: String,
        conversationId: String,
        peerUserId: String,
        isVideo: Boolean,
        iceServers: List<IceServerConfig>,
        onStageChanged: (WebRtcConnectionStage) -> Unit
    ) {
        currentCallId = callId
        currentConversationId = conversationId
        currentPeerUserId = peerUserId
        isVideoCall = isVideo
        hasRemoteDescription = false
        pendingIceCandidates.clear()

        configureAudioRouting(speakerOn = isVideo)
        ensureFactoryInitialized()

        val rtcIceServers = iceServers.map { cfg ->
            val builder = PeerConnection.IceServer.builder(cfg.urls)
            if (!cfg.username.isNullOrBlank() && !cfg.credential.isNullOrBlank()) {
                builder.setUsername(cfg.username).setPassword(cfg.credential)
            }
            builder.createIceServer()
        }

        val rtcConfig = PeerConnection.RTCConfiguration(rtcIceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = factory?.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate) {
                    val payload = JSONObject().apply {
                        put("callId", currentCallId)
                        put("conversationId", currentConversationId)
                        put("targetUserId", currentPeerUserId)
                        put("sdpMid", candidate.sdpMid)
                        put("sdpMLineIndex", candidate.sdpMLineIndex)
                        put("candidate", candidate.sdp)
                    }
                    socketManager.sendSignaling("call.ice", payload)
                }

                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                    SafeLogger.d("WebRTC", "ICE state=$state")
                    when (state) {
                        PeerConnection.IceConnectionState.CHECKING -> onStageChanged(WebRtcConnectionStage.CONNECTING)
                        PeerConnection.IceConnectionState.CONNECTED,
                        PeerConnection.IceConnectionState.COMPLETED -> onStageChanged(WebRtcConnectionStage.CONNECTED)
                        PeerConnection.IceConnectionState.DISCONNECTED -> onStageChanged(WebRtcConnectionStage.RECONNECTING)
                        PeerConnection.IceConnectionState.FAILED -> onStageChanged(WebRtcConnectionStage.FAILED)
                        PeerConnection.IceConnectionState.CLOSED -> onStageChanged(WebRtcConnectionStage.ENDED)
                        else -> Unit
                    }
                }

                override fun onTrack(transceiver: org.webrtc.RtpTransceiver?) {
                    val track = transceiver?.receiver?.track()
                    if (track is VideoTrack) {
                        remoteSink?.let { track.addSink(it) }
                    }
                }

                override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                    val track = receiver?.track()
                    if (track is VideoTrack) {
                        remoteSink?.let { track.addSink(it) }
                    }
                }

                override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
                override fun onIceConnectionReceivingChange(p0: Boolean) {}
                override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
                override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
                override fun onAddStream(p0: MediaStream?) {}
                override fun onRemoveStream(p0: MediaStream?) {}
                override fun onDataChannel(p0: DataChannel?) {}
                override fun onRenegotiationNeeded() {}
            }
        )

        setupLocalMediaTracks(isVideo)
    }

    fun createAndSendOffer() {
        val pc = peerConnection ?: return
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", if (isVideoCall) "true" else "false"))
        }
        pc.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                pc.setLocalDescription(SdpObserverAdapter(), sdp)
                val payload = JSONObject().apply {
                    put("callId", currentCallId)
                    put("conversationId", currentConversationId)
                    put("targetUserId", currentPeerUserId)
                    put("sdp", sdp.description)
                }
                socketManager.sendSignaling("call.offer", payload)
            }
        }, constraints)
    }

    fun handleRemoteOfferAndSendAnswer(sdpText: String) {
        val pc = peerConnection ?: return
        val remoteDesc = SessionDescription(SessionDescription.Type.OFFER, sdpText)
        pc.setRemoteDescription(object : SdpObserverAdapter() {
            override fun onSetSuccess() {
                hasRemoteDescription = true
                flushIceCandidates()
                val constraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", if (isVideoCall) "true" else "false"))
                }
                pc.createAnswer(object : SdpObserverAdapter() {
                    override fun onCreateSuccess(answer: SessionDescription) {
                        pc.setLocalDescription(SdpObserverAdapter(), answer)
                        val payload = JSONObject().apply {
                            put("callId", currentCallId)
                            put("conversationId", currentConversationId)
                            put("targetUserId", currentPeerUserId)
                            put("sdp", answer.description)
                        }
                        socketManager.sendSignaling("call.answer", payload)
                    }
                }, constraints)
            }
        }, remoteDesc)
    }

    fun handleRemoteAnswer(sdpText: String) {
        val pc = peerConnection ?: return
        val remoteDesc = SessionDescription(SessionDescription.Type.ANSWER, sdpText)
        pc.setRemoteDescription(object : SdpObserverAdapter() {
            override fun onSetSuccess() {
                hasRemoteDescription = true
                flushIceCandidates()
            }
        }, remoteDesc)
    }

    fun handleRemoteIceCandidate(sdpMid: String, sdpMLineIndex: Int, candidateSdp: String) {
        val candidate = IceCandidate(sdpMid, sdpMLineIndex, candidateSdp)
        if (hasRemoteDescription) {
            peerConnection?.addIceCandidate(candidate)
        } else {
            pendingIceCandidates.add(candidate)
        }
    }

    fun setMicEnabled(enabled: Boolean) {
        localAudioTrack?.setEnabled(enabled)
    }

    fun setCameraEnabled(enabled: Boolean) {
        localVideoTrack?.setEnabled(enabled)
    }

    fun switchCamera() {
        videoCapturer?.switchCamera(null)
    }

    fun configureAudioRouting(speakerOn: Boolean) {
        runCatching {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = speakerOn
        }
    }

    fun endSession() {
        runCatching { videoCapturer?.stopCapture() }
        runCatching { videoCapturer?.dispose() }
        videoCapturer = null

        runCatching { surfaceTextureHelper?.dispose() }
        surfaceTextureHelper = null

        localSink?.let { runCatching { localVideoTrack?.removeSink(it) } }
        localVideoTrack?.dispose()
        localVideoTrack = null
        videoSource?.dispose()
        videoSource = null

        localAudioTrack?.dispose()
        localAudioTrack = null
        audioSource?.dispose()
        audioSource = null

        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null

        runCatching {
            audioManager.isSpeakerphoneOn = false
            audioManager.mode = AudioManager.MODE_NORMAL
        }
    }

    fun release() {
        endSession()
        factory?.dispose()
        factory = null
        runCatching { eglBase.release() }
    }

    private fun ensureFactoryInitialized() {
        if (factory != null) return
        val initOptions = PeerConnectionFactory.InitializationOptions.builder(context)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initOptions)

        val audioModule = JavaAudioDeviceModule.builder(context).createAudioDeviceModule()
        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioModule)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
        audioModule.release()
    }

    private fun setupLocalMediaTracks(isVideo: Boolean) {
        val f = factory ?: return
        val pc = peerConnection ?: return

        val audioSrc = f.createAudioSource(MediaConstraints())
        val audioTrack = f.createAudioTrack("MCP_AUDIO_0", audioSrc)
        audioTrack.setEnabled(true)
        pc.addTrack(audioTrack, listOf("MCP_STREAM"))
        audioSource = audioSrc
        localAudioTrack = audioTrack

        if (isVideo) {
            val enumerator = Camera2Enumerator(context)
            val deviceName = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
                ?: enumerator.deviceNames.firstOrNull()
            if (deviceName != null) {
                val capturer = enumerator.createCapturer(deviceName, null)
                if (capturer != null) {
                    val texHelper = SurfaceTextureHelper.create("MCPCaptureThread", eglBase.eglBaseContext)
                    val vSource = f.createVideoSource(capturer.isScreencast)
                    capturer.initialize(texHelper, context, vSource.capturerObserver)
                    capturer.startCapture(720, 1280, 30)

                    val vTrack = f.createVideoTrack("MCP_VIDEO_0", vSource)
                    vTrack.setEnabled(true)
                    localSink?.let { vTrack.addSink(it) }
                    pc.addTrack(vTrack, listOf("MCP_STREAM"))

                    videoCapturer = capturer
                    surfaceTextureHelper = texHelper
                    videoSource = vSource
                    localVideoTrack = vTrack
                }
            }
        }
    }

    private fun flushIceCandidates() {
        val pc = peerConnection ?: return
        pendingIceCandidates.forEach { pc.addIceCandidate(it) }
        pendingIceCandidates.clear()
    }

    private open class SdpObserverAdapter : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(err: String?) {
            SafeLogger.e("WebRTC", "SDP create failed: $err")
        }
        override fun onSetFailure(err: String?) {
            SafeLogger.e("WebRTC", "SDP set failed: $err")
        }
    }
}
