package com.mychattingpro.features.calls

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

enum class WebRtcConnectionStage {
    IDLE, CALLING, RINGING, CONNECTING, CONNECTED, RECONNECTING, ENDED, FAILED
}

/**
 * Genuine WebRTC Audio & Video Call Engine powered by Stream WebRTC Android (`org.webrtc.*`)
 * and Cloudflare Durable Objects WebSocket signaling.
 */
open class WebRtcCallManager(
    private val context: Context,
    private val socketManager: RealtimeSocketManager
) {
    val eglBase: EglBase by lazy { EglBase.create() }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val rtcExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "MCPWebRtcThread").apply { isDaemon = true }
    }
    private val sessionGen = AtomicLong(0L)

    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var remoteVideoTrack: VideoTrack? = null
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
    private var hasConnectedInSession = false
    private var isSpeakerPreferred = false

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { }

    open fun attachVideoSinks(local: VideoSink?, remote: VideoSink?) {
        mainHandler.post {
            localSink?.let { old -> runCatching { localVideoTrack?.removeSink(old) } }
            remoteSink?.let { old -> runCatching { remoteVideoTrack?.removeSink(old) } }
            localSink = local
            remoteSink = remote
            local?.let { runCatching { localVideoTrack?.addSink(it) } }
            remote?.let { runCatching { remoteVideoTrack?.addSink(it) } }
        }
    }

    open fun startSession(
        callId: String,
        conversationId: String,
        peerUserId: String,
        isVideo: Boolean,
        iceServers: List<IceServerConfig>,
        onStageChanged: (WebRtcConnectionStage) -> Unit
    ): Boolean {
        val gen = sessionGen.incrementAndGet()
        currentCallId = callId
        currentConversationId = conversationId
        currentPeerUserId = peerUserId
        isVideoCall = isVideo
        isSpeakerPreferred = isVideo
        hasRemoteDescription = false
        hasConnectedInSession = false

        synchronized(pendingIceCandidates) {
            pendingIceCandidates.clear()
        }

        return try {
            requestCallAudioFocus()
            configureAudioRouting(speakerOn = isVideo)

            val task = rtcExecutor.submit<Boolean> {
                if (gen != sessionGen.get()) return@submit false
                internalCleanupPeerResources()
                ensureFactoryInitialized()

                val effectiveServers = iceServers.ifEmpty {
                    listOf(
                        IceServerConfig(
                            urls = listOf(
                                "stun:stun.cloudflare.com:3478",
                                "stun:stun.l.google.com:19302",
                                "stun:stun1.l.google.com:19302",
                                "stun:stun2.l.google.com:19302"
                            )
                        )
                    )
                }
                val rtcIceServers = effectiveServers.map { cfg ->
                    val builder = PeerConnection.IceServer.builder(cfg.urls)
                    if (!cfg.username.isNullOrBlank() && !cfg.credential.isNullOrBlank()) {
                        builder.setUsername(cfg.username).setPassword(cfg.credential)
                    }
                    builder.createIceServer()
                }

                val rtcConfig = PeerConnection.RTCConfiguration(rtcIceServers).apply {
                    sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                    continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
                    bundlePolicy = PeerConnection.BundlePolicy.BALANCED
                    rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
                    tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
                    iceCandidatePoolSize = 4
                }

                val createdPc = factory?.createPeerConnection(
                    rtcConfig,
                    object : PeerConnection.Observer {
                        override fun onIceCandidate(candidate: IceCandidate) {
                            if (gen != sessionGen.get()) return
                            val payload = JSONObject().apply {
                                put("callId", callId)
                                put("conversationId", conversationId)
                                put("targetUserId", peerUserId)
                                put("sdpMid", candidate.sdpMid)
                                put("sdpMLineIndex", candidate.sdpMLineIndex)
                                put("candidate", candidate.sdp)
                            }
                            socketManager.sendSignaling("call.ice", payload)
                        }

                        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                            if (gen != sessionGen.get()) return
                            SafeLogger.d("WebRTC", "ICE state=$state")
                            when (state) {
                                PeerConnection.IceConnectionState.CHECKING -> {
                                    if (!hasConnectedInSession) {
                                        onStageChanged(WebRtcConnectionStage.CONNECTING)
                                    }
                                }
                                PeerConnection.IceConnectionState.CONNECTED,
                                PeerConnection.IceConnectionState.COMPLETED -> {
                                    hasConnectedInSession = true
                                    configureAudioRouting(speakerOn = isSpeakerPreferred)
                                    onStageChanged(WebRtcConnectionStage.CONNECTED)
                                }
                                PeerConnection.IceConnectionState.DISCONNECTED -> {
                                    if (hasConnectedInSession) {
                                        onStageChanged(WebRtcConnectionStage.RECONNECTING)
                                    }
                                }
                                PeerConnection.IceConnectionState.FAILED -> onStageChanged(WebRtcConnectionStage.FAILED)
                                PeerConnection.IceConnectionState.CLOSED -> onStageChanged(WebRtcConnectionStage.ENDED)
                                else -> Unit
                            }
                        }

                        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                            if (gen != sessionGen.get()) return
                            SafeLogger.d("WebRTC", "PeerConnection state=$newState")
                            when (newState) {
                                PeerConnection.PeerConnectionState.CONNECTING -> {
                                    if (!hasConnectedInSession) {
                                        onStageChanged(WebRtcConnectionStage.CONNECTING)
                                    }
                                }
                                PeerConnection.PeerConnectionState.CONNECTED -> {
                                    hasConnectedInSession = true
                                    configureAudioRouting(speakerOn = isSpeakerPreferred)
                                    onStageChanged(WebRtcConnectionStage.CONNECTED)
                                }
                                PeerConnection.PeerConnectionState.DISCONNECTED -> {
                                    if (hasConnectedInSession) {
                                        onStageChanged(WebRtcConnectionStage.RECONNECTING)
                                    }
                                }
                                PeerConnection.PeerConnectionState.FAILED -> onStageChanged(WebRtcConnectionStage.FAILED)
                                PeerConnection.PeerConnectionState.CLOSED -> onStageChanged(WebRtcConnectionStage.ENDED)
                                else -> Unit
                            }
                        }

                        override fun onTrack(transceiver: org.webrtc.RtpTransceiver?) {
                            if (gen != sessionGen.get()) return
                            val track = transceiver?.receiver?.track()
                            when (track) {
                                is AudioTrack -> runCatching {
                                    track.setEnabled(true)
                                    track.setVolume(1.0)
                                }
                                is VideoTrack -> bindRemoteVideoTrack(track)
                            }
                        }

                        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                            if (gen != sessionGen.get()) return
                            val track = receiver?.track()
                            when (track) {
                                is AudioTrack -> runCatching {
                                    track.setEnabled(true)
                                    track.setVolume(1.0)
                                }
                                is VideoTrack -> bindRemoteVideoTrack(track)
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
                if (createdPc == null) {
                    SafeLogger.e("WebRTC", "createPeerConnection returned null")
                    return@submit false
                }
                peerConnection = createdPc
                setupLocalMediaTracks(isVideo)
                true
            }
            val ok = task.get()
            if (!ok) {
                endSession()
            }
            ok
        } catch (t: Throwable) {
            SafeLogger.e("WebRTC", "startSession failed", t.javaClass.simpleName)
            endSession()
            false
        }
    }

    private fun bindRemoteVideoTrack(track: VideoTrack) {
        mainHandler.post {
            remoteSink?.let { oldSink ->
                runCatching { remoteVideoTrack?.removeSink(oldSink) }
            }
            remoteVideoTrack = track
            track.setEnabled(true)
            remoteSink?.let { sink ->
                runCatching { track.addSink(sink) }
            }
        }
    }

    open fun createAndSendOffer(iceRestart: Boolean = false) {
        val gen = sessionGen.get()
        rtcExecutor.execute {
            if (gen != sessionGen.get()) return@execute
            val pc = peerConnection ?: return@execute
            val state = pc.signalingState()
            if (state != PeerConnection.SignalingState.STABLE && !iceRestart) {
                val existingLocalSdp = pc.localDescription
                if (existingLocalSdp?.type == SessionDescription.Type.OFFER) {
                    sendOfferPayload(existingLocalSdp.description)
                }
                return@execute
            }

            val constraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", if (isVideoCall) "true" else "false"))
                if (iceRestart) {
                    mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
                }
            }
            pc.createOffer(object : SdpObserverAdapter() {
                override fun onCreateSuccess(sdp: SessionDescription) {
                    rtcExecutor.execute {
                        if (gen != sessionGen.get()) return@execute
                        val activePc = peerConnection ?: return@execute
                        activePc.setLocalDescription(object : SdpObserverAdapter() {
                            override fun onSetSuccess() {
                                if (gen != sessionGen.get()) return
                                sendOfferPayload(activePc.localDescription?.description ?: sdp.description)
                            }
                        }, sdp)
                    }
                }
            }, constraints)
        }
    }

    private fun sendOfferPayload(sdpDescription: String) {
        val payload = JSONObject().apply {
            put("callId", currentCallId)
            put("conversationId", currentConversationId)
            put("targetUserId", currentPeerUserId)
            put("sdp", sdpDescription)
        }
        socketManager.sendSignaling("call.offer", payload)
    }

    open fun handleRemoteOfferAndSendAnswer(sdpText: String) {
        val gen = sessionGen.get()
        rtcExecutor.execute {
            if (gen != sessionGen.get()) return@execute
            val pc = peerConnection ?: return@execute
            val sigState = pc.signalingState()
            if (sigState == PeerConnection.SignalingState.HAVE_LOCAL_OFFER) {
                // Rollback any stale local offer before applying remote offer
                pc.setLocalDescription(object : SdpObserverAdapter() {
                    override fun onSetSuccess() {
                        rtcExecutor.execute {
                            if (gen == sessionGen.get()) {
                                applyRemoteOfferAndAnswer(pc, gen, sdpText)
                            }
                        }
                    }
                }, SessionDescription(SessionDescription.Type.ROLLBACK, ""))
                return@execute
            }
            applyRemoteOfferAndAnswer(pc, gen, sdpText)
        }
    }

    private fun applyRemoteOfferAndAnswer(pc: PeerConnection, gen: Long, sdpText: String) {
        val remoteDesc = SessionDescription(SessionDescription.Type.OFFER, sdpText)
        pc.setRemoteDescription(object : SdpObserverAdapter() {
            override fun onSetSuccess() {
                rtcExecutor.execute {
                    if (gen != sessionGen.get()) return@execute
                    hasRemoteDescription = true
                    flushIceCandidates()
                    val constraints = MediaConstraints().apply {
                        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", if (isVideoCall) "true" else "false"))
                    }
                    pc.createAnswer(object : SdpObserverAdapter() {
                        override fun onCreateSuccess(answer: SessionDescription) {
                            rtcExecutor.execute {
                                if (gen != sessionGen.get()) return@execute
                                val activePc = peerConnection ?: return@execute
                                activePc.setLocalDescription(object : SdpObserverAdapter() {
                                    override fun onSetSuccess() {
                                        if (gen != sessionGen.get()) return
                                        val payload = JSONObject().apply {
                                            put("callId", currentCallId)
                                            put("conversationId", currentConversationId)
                                            put("targetUserId", currentPeerUserId)
                                            put("sdp", activePc.localDescription?.description ?: answer.description)
                                        }
                                        socketManager.sendSignaling("call.answer", payload)
                                    }
                                }, answer)
                            }
                        }
                    }, constraints)
                }
            }
        }, remoteDesc)
    }

    open fun handleRemoteAnswer(sdpText: String) {
        val gen = sessionGen.get()
        rtcExecutor.execute {
            if (gen != sessionGen.get()) return@execute
            val pc = peerConnection ?: return@execute
            if (pc.signalingState() != PeerConnection.SignalingState.HAVE_LOCAL_OFFER) {
                return@execute
            }
            val remoteDesc = SessionDescription(SessionDescription.Type.ANSWER, sdpText)
            pc.setRemoteDescription(object : SdpObserverAdapter() {
                override fun onSetSuccess() {
                    rtcExecutor.execute {
                        if (gen != sessionGen.get()) return@execute
                        hasRemoteDescription = true
                        flushIceCandidates()
                    }
                }
            }, remoteDesc)
        }
    }

    open fun handleRemoteIceCandidate(sdpMid: String, sdpMLineIndex: Int, candidateSdp: String) {
        val gen = sessionGen.get()
        val candidate = IceCandidate(sdpMid, sdpMLineIndex, candidateSdp)
        rtcExecutor.execute {
            if (gen != sessionGen.get()) return@execute
            val pc = peerConnection
            if (hasRemoteDescription && pc != null) {
                runCatching { pc.addIceCandidate(candidate) }
            } else {
                synchronized(pendingIceCandidates) {
                    pendingIceCandidates.add(candidate)
                }
            }
        }
    }

    open fun setMicEnabled(enabled: Boolean) {
        rtcExecutor.execute {
            runCatching { localAudioTrack?.setEnabled(enabled) }
        }
    }

    open fun setCameraEnabled(enabled: Boolean) {
        rtcExecutor.execute {
            runCatching { localVideoTrack?.setEnabled(enabled) }
        }
    }

    open fun switchCamera() {
        rtcExecutor.execute {
            runCatching { videoCapturer?.switchCamera(null) }
        }
    }

    open fun configureAudioRouting(speakerOn: Boolean) {
        isSpeakerPreferred = speakerOn
        mainHandler.post {
            runCatching {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val devices = audioManager.availableCommunicationDevices
                    val preferredDevice = if (speakerOn) {
                        devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    } else {
                        devices.firstOrNull {
                            it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                                it.type == AudioDeviceInfo.TYPE_USB_HEADSET
                        } ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                    }
                    if (preferredDevice != null) {
                        audioManager.setCommunicationDevice(preferredDevice)
                    }
                }
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = speakerOn
            }
        }
    }

    open fun endSession() {
        sessionGen.incrementAndGet()
        rtcExecutor.execute {
            internalCleanupPeerResources()
        }
        mainHandler.post {
            restoreAudioRoutingAndFocus()
        }
    }

    open fun release() {
        sessionGen.incrementAndGet()
        rtcExecutor.execute {
            internalCleanupPeerResources()
            runCatching { factory?.dispose() }
            factory = null
        }
        mainHandler.post {
            restoreAudioRoutingAndFocus()
        }
        runCatching { eglBase.release() }
    }

    private fun internalCleanupPeerResources() {
        runCatching { videoCapturer?.stopCapture() }
        runCatching { videoCapturer?.dispose() }
        videoCapturer = null

        runCatching { surfaceTextureHelper?.dispose() }
        surfaceTextureHelper = null

        remoteSink?.let { sink -> runCatching { remoteVideoTrack?.removeSink(sink) } }
        remoteVideoTrack = null

        localSink?.let { sink -> runCatching { localVideoTrack?.removeSink(sink) } }
        runCatching { localVideoTrack?.dispose() }
        localVideoTrack = null
        runCatching { videoSource?.dispose() }
        videoSource = null

        runCatching { localAudioTrack?.dispose() }
        localAudioTrack = null
        runCatching { audioSource?.dispose() }
        audioSource = null

        runCatching { peerConnection?.close() }
        runCatching { peerConnection?.dispose() }
        peerConnection = null
        hasRemoteDescription = false
        hasConnectedInSession = false
        synchronized(pendingIceCandidates) {
            pendingIceCandidates.clear()
        }
    }

    private fun requestCallAudioFocus() {
        mainHandler.post {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    if (audioFocusRequest == null) {
                        val attrs = AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                        audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                            .setAudioAttributes(attrs)
                            .setOnAudioFocusChangeListener(audioFocusChangeListener)
                            .setWillPauseWhenDucked(false)
                            .build()
                    }
                    audioFocusRequest?.let { audioManager.requestAudioFocus(it) }
                } else {
                    @Suppress("DEPRECATION")
                    audioManager.requestAudioFocus(
                        audioFocusChangeListener,
                        AudioManager.STREAM_VOICE_CALL,
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                    )
                }
            }
        }
    }

    private fun restoreAudioRoutingAndFocus() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            }
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = false
            audioManager.mode = AudioManager.MODE_NORMAL
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                audioFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(audioFocusChangeListener)
            }
        }
    }

    private fun ensureFactoryInitialized() {
        if (factory != null) return
        val initOptions = PeerConnectionFactory.InitializationOptions.builder(context)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initOptions)

        val audioModule = runCatching {
            JavaAudioDeviceModule.builder(context)
                .setUseHardwareAcousticEchoCanceler(JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported())
                .setUseHardwareNoiseSuppressor(JavaAudioDeviceModule.isBuiltInNoiseSuppressorSupported())
                .setUseStereoInput(false)
                .setUseStereoOutput(false)
                .createAudioDeviceModule()
        }.getOrElse {
            JavaAudioDeviceModule.builder(context)
                .setUseStereoInput(false)
                .setUseStereoOutput(false)
                .createAudioDeviceModule()
        }
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

        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation2", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl2", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression2", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googTypingNoiseDetection", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAudioMirroring", "false"))
        }
        val audioSrc = f.createAudioSource(audioConstraints)
        val audioTrack = f.createAudioTrack("MCP_AUDIO_0", audioSrc)
        audioTrack.setEnabled(true)
        pc.addTrack(audioTrack, listOf("MCP_STREAM"))
        audioSource = audioSrc
        localAudioTrack = audioTrack

        if (isVideo) {
            runCatching {
                val enumerator = Camera2Enumerator(context)
                val deviceName = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
                    ?: enumerator.deviceNames.firstOrNull()
                if (deviceName != null) {
                    val capturer = enumerator.createCapturer(deviceName, null)
                    if (capturer != null) {
                        val formats = runCatching { enumerator.getSupportedFormats(deviceName) }.getOrNull().orEmpty()
                        val chosen = formats.firstOrNull { it.width == 1280 && it.height == 720 }
                            ?: formats.firstOrNull { it.width == 640 && it.height == 480 }
                            ?: formats.firstOrNull()
                        val capW = chosen?.width ?: 640
                        val capH = chosen?.height ?: 480
                        val capFps = chosen?.framerate?.max?.let { (it / 1000).coerceIn(15, 30) } ?: 30

                        val texHelper = SurfaceTextureHelper.create("MCPCaptureThread", eglBase.eglBaseContext)
                        val vSource = f.createVideoSource(capturer.isScreencast)
                        capturer.initialize(texHelper, context, vSource.capturerObserver)
                        capturer.startCapture(capW, capH, capFps)

                        val vTrack = f.createVideoTrack("MCP_VIDEO_0", vSource)
                        vTrack.setEnabled(true)
                        mainHandler.post {
                            localSink?.let { runCatching { vTrack.addSink(it) } }
                        }
                        pc.addTrack(vTrack, listOf("MCP_STREAM"))

                        videoCapturer = capturer
                        surfaceTextureHelper = texHelper
                        videoSource = vSource
                        localVideoTrack = vTrack
                    }
                }
            }.onFailure { err ->
                SafeLogger.w("WebRTC", "Camera capture fallback without local video: ${err.javaClass.simpleName}")
            }
        }
    }

    private fun flushIceCandidates() {
        val pc = peerConnection ?: return
        val snapshot = synchronized(pendingIceCandidates) {
            val copy = pendingIceCandidates.toList()
            pendingIceCandidates.clear()
            copy
        }
        snapshot.forEach { runCatching { pc.addIceCandidate(it) } }
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
