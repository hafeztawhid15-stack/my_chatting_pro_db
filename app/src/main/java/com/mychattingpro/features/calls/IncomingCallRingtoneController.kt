package com.mychattingpro.features.calls

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import com.mychattingpro.core.security.SafeLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

interface RingtonePlaybackEngine {
    fun resolveCurrentSystemRingtoneUri(): Uri?
    fun resolveFallbackSystemRingtoneUris(): List<Uri> = listOfNotNull(resolveCurrentSystemRingtoneUri())
    fun getRingerMode(): Int
    fun startPlayback(uri: Uri?, vibrate: Boolean)
    fun stopPlayback()
}

class AndroidRingtonePlaybackEngine(
    private val context: Context
) : RingtonePlaybackEngine {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var ringtone: Ringtone? = null
    private var mediaPlayer: MediaPlayer? = null
    private var fallbackToneGenerator: android.media.ToneGenerator? = null
    private var loopCheckRunnable: Runnable? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    @Volatile
    private var playbackGeneration: Long = 0L

    private val ringAudioAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    override fun resolveCurrentSystemRingtoneUri(): Uri? {
        return resolveFallbackSystemRingtoneUris().firstOrNull()
    }

    override fun resolveFallbackSystemRingtoneUris(): List<Uri> {
        val uris = linkedSetOf<Uri>()
        runCatching {
            RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
        }.getOrNull()?.let { uris.add(it) }
        runCatching {
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        }.getOrNull()?.let { uris.add(it) }
        uris.add(Settings.System.DEFAULT_RINGTONE_URI)
        runCatching {
            RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_NOTIFICATION)
        }.getOrNull()?.let { uris.add(it) }
        runCatching {
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        }.getOrNull()?.let { uris.add(it) }
        uris.add(Settings.System.DEFAULT_NOTIFICATION_URI)
        return uris.toList()
    }

    override fun getRingerMode(): Int = runCatching {
        audioManager?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
    }.getOrDefault(AudioManager.RINGER_MODE_NORMAL)

    override fun startPlayback(uri: Uri?, vibrate: Boolean) {
        val gen = ++playbackGeneration
        val block = {
            if (playbackGeneration == gen) {
                startPlaybackOnMain(gen, uri, vibrate)
            }
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    override fun stopPlayback() {
        playbackGeneration++
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            stopPlaybackOnMain()
        } else {
            mainHandler.post { stopPlaybackOnMain() }
        }
    }

    @Synchronized
    private fun startPlaybackOnMain(gen: Long, uri: Uri?, vibrate: Boolean) {
        stopPlaybackOnMain()
        if (playbackGeneration != gen) return

        if (uri != null) {
            requestRingtoneFocus()

            val candidates = (listOf(uri) + resolveFallbackSystemRingtoneUris()).distinct()
            var startedAudio = false

            // Pass 1: Android system Ringtone (IRingtonePlayer) handles user-selected OEM theme URIs,
            // Dual-SIM tones, and Android 11-15 scoped storage URIs without SecurityException.
            for (candidate in candidates) {
                val played = runCatching {
                    val rt = RingtoneManager.getRingtone(context, candidate) ?: return@runCatching false
                    rt.audioAttributes = ringAudioAttributes
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        rt.isLooping = true
                    }
                    rt.play()
                    ringtone = rt
                    scheduleRingtoneLoopKeeper(gen, rt)
                    true
                }.getOrDefault(false)
                if (played) {
                    startedAudio = true
                    break
                }
            }

            // Pass 2: MediaPlayer fallback if Ringtone service was unavailable
            if (!startedAudio) {
                for (candidate in candidates) {
                    val mp = MediaPlayer()
                    var cleanedUp = false
                    val played = runCatching {
                        mp.setAudioAttributes(ringAudioAttributes)
                        mp.setDataSource(context, candidate)
                        mp.isLooping = true
                        mp.setVolume(1.0f, 1.0f)
                        mp.setOnErrorListener { failedMp, _, _ ->
                            runCatching { failedMp.release() }
                            if (mediaPlayer === failedMp) {
                                mediaPlayer = null
                            }
                            true
                        }
                        mp.prepare()
                        mp.start()
                        mediaPlayer = mp
                        true
                    }.onFailure {
                        if (!cleanedUp) {
                            cleanedUp = true
                            runCatching { mp.reset() }
                            runCatching { mp.release() }
                        }
                    }.getOrDefault(false)
                    if (played) {
                        startedAudio = true
                        break
                    }
                }
            }

            // Pass 3: Synthetic ringback tone fallback so devices with stripped sound providers still ring
            if (!startedAudio) {
                startToneGeneratorFallback(gen)
            }
        }

        if (vibrate) {
            startVibration()
        }
    }

    private fun scheduleRingtoneLoopKeeper(gen: Long, rt: Ringtone) {
        loopCheckRunnable?.let { mainHandler.removeCallbacks(it) }
        val checker = object : Runnable {
            override fun run() {
                if (playbackGeneration != gen) return
                runCatching {
                    if (!rt.isPlaying) {
                        rt.play()
                    }
                }
                mainHandler.postDelayed(this, 3200L)
            }
        }
        loopCheckRunnable = checker
        mainHandler.postDelayed(checker, 3200L)
    }

    private fun startToneGeneratorFallback(gen: Long) {
        val tg = runCatching {
            android.media.ToneGenerator(AudioManager.STREAM_RING, 85)
        }.getOrNull() ?: return
        fallbackToneGenerator = tg
        val pulse = object : Runnable {
            override fun run() {
                if (playbackGeneration != gen) return
                runCatching { tg.startTone(android.media.ToneGenerator.TONE_SUP_RINGTONE, 1800) }
                mainHandler.postDelayed(this, 3000L)
            }
        }
        loopCheckRunnable = pulse
        pulse.run()
    }

    @Synchronized
    private fun stopPlaybackOnMain() {
        loopCheckRunnable?.let { mainHandler.removeCallbacks(it) }
        loopCheckRunnable = null

        runCatching { ringtone?.stop() }
        ringtone = null

        runCatching {
            mediaPlayer?.let { mp ->
                if (mp.isPlaying) mp.stop()
                mp.release()
            }
        }
        mediaPlayer = null

        runCatching {
            fallbackToneGenerator?.stopTone()
            fallbackToneGenerator?.release()
        }
        fallbackToneGenerator = null

        stopVibration()
        abandonRingtoneFocus()
    }

    private fun requestRingtoneFocus() {
        val am = audioManager ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(ringAudioAttributes)
                    .setWillPauseWhenDucked(false)
                    .build()
                audioFocusRequest = req
                am.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(null, AudioManager.STREAM_RING, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            }
        }
    }

    private fun abandonRingtoneFocus() {
        val am = audioManager ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
                audioFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(null)
            }
        }
    }

    private fun getVibrator(): Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }.getOrNull()

    private fun startVibration() {
        val vibrator = getVibrator() ?: return
        if (!vibrator.hasVibrator()) return
        val pattern = longArrayOf(0L, 600L, 900L)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val attrs = VibrationAttributes.Builder()
                    .setUsage(VibrationAttributes.USAGE_RINGTONE)
                    .build()
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0), attrs)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                @Suppress("DEPRECATION")
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0), ringAudioAttributes)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, 0)
            }
        }
    }

    private fun stopVibration() {
        runCatching { getVibrator()?.cancel() }
    }
}

/**
 * Plays the device's current System Default SIM Call Ringtone on every incoming Voice/Video call.
 * Dynamically re-queries RingtoneManager on every call so Android Settings changes apply immediately.
 * Guarantees immediate stop when accepted, rejected, disconnected, or timed out.
 */
class IncomingCallRingtoneController(
    context: Context,
    private val engine: RingtonePlaybackEngine = AndroidRingtonePlaybackEngine(context.applicationContext),
    private val maxRingDurationMs: Long = 60_000L
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _isRinging = MutableStateFlow(false)
    val isRinging: StateFlow<Boolean> = _isRinging.asStateFlow()

    var currentCallId: String? = null
        private set

    var lastPlayedUri: Uri? = null
        private set

    private var autoStopJob: Job? = null

    @Synchronized
    fun startRinging(callId: String, allowVibration: Boolean = true) {
        if (currentCallId == callId && _isRinging.value) return

        stopRinging(force = true)

        val ringerMode = engine.getRingerMode()
        if (ringerMode == AudioManager.RINGER_MODE_SILENT) {
            currentCallId = callId
            lastPlayedUri = null
            _isRinging.value = false
            return
        }

        val currentSystemUri = if (ringerMode == AudioManager.RINGER_MODE_NORMAL) {
            engine.resolveCurrentSystemRingtoneUri()
        } else {
            null
        }
        val shouldVibrate = allowVibration && (
            ringerMode == AudioManager.RINGER_MODE_VIBRATE ||
                ringerMode == AudioManager.RINGER_MODE_NORMAL
            )

        if (currentSystemUri == null && !shouldVibrate) {
            currentCallId = callId
            lastPlayedUri = null
            _isRinging.value = false
            return
        }

        runCatching {
            engine.startPlayback(uri = currentSystemUri, vibrate = shouldVibrate)
            currentCallId = callId
            lastPlayedUri = currentSystemUri
            _isRinging.value = true
            scheduleAutoStop(callId)
        }.onFailure { err ->
            SafeLogger.w("IncomingCallRingtone", "Unable to start system ringtone: ${err.javaClass.simpleName}")
            stopRinging(force = true)
        }
    }

    @Synchronized
    fun stopRinging(callId: String? = null, force: Boolean = false) {
        if (!force && callId != null && currentCallId != null && callId != currentCallId) {
            return
        }
        autoStopJob?.cancel()
        autoStopJob = null
        runCatching { engine.stopPlayback() }
        currentCallId = null
        _isRinging.value = false
    }

    private fun scheduleAutoStop(callId: String) {
        autoStopJob?.cancel()
        autoStopJob = scope.launch {
            delay(maxRingDurationMs)
            if (currentCallId == callId) {
                stopRinging(callId = callId)
            }
        }
    }
}
