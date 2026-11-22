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
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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
    fun getRingerMode(): Int
    fun startPlayback(uri: Uri?, vibrate: Boolean)
    fun stopPlayback()
}

class AndroidRingtonePlaybackEngine(
    private val context: Context
) : RingtonePlaybackEngine {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var ringtone: Ringtone? = null
    private var mediaPlayer: MediaPlayer? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var previousAudioMode: Int? = null

    private val ringAudioAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    override fun resolveCurrentSystemRingtoneUri(): Uri? {
        val actual = runCatching {
            RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
        }.getOrNull()
        return actual ?: runCatching {
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        }.getOrNull()
    }

    override fun getRingerMode(): Int = runCatching {
        audioManager?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
    }.getOrDefault(AudioManager.RINGER_MODE_NORMAL)

    @Synchronized
    override fun startPlayback(uri: Uri?, vibrate: Boolean) {
        stopPlayback()

        if (uri != null) {
            requestRingtoneFocusAndMode()

            var startedAudio = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                startedAudio = runCatching {
                    val rt = RingtoneManager.getRingtone(context, uri) ?: return@runCatching false
                    rt.audioAttributes = ringAudioAttributes
                    rt.isLooping = true
                    rt.play()
                    ringtone = rt
                    true
                }.getOrDefault(false)
            }

            if (!startedAudio) {
                startedAudio = runCatching {
                    val mp = MediaPlayer()
                    mp.setAudioAttributes(ringAudioAttributes)
                    mp.setDataSource(context, uri)
                    mp.isLooping = true
                    mp.prepare()
                    mp.start()
                    mediaPlayer = mp
                    true
                }.onFailure {
                    runCatching { mediaPlayer?.release() }
                    mediaPlayer = null
                }.getOrDefault(false)
            }

            if (!startedAudio) {
                runCatching {
                    val rt = RingtoneManager.getRingtone(context, uri)
                    if (rt != null) {
                        rt.audioAttributes = ringAudioAttributes
                        rt.play()
                        ringtone = rt
                    }
                }
            }
        }

        if (vibrate) {
            startVibration()
        }
    }

    @Synchronized
    override fun stopPlayback() {
        runCatching { ringtone?.stop() }
        ringtone = null

        runCatching {
            mediaPlayer?.let { mp ->
                if (mp.isPlaying) mp.stop()
                mp.release()
            }
        }
        mediaPlayer = null

        stopVibration()
        abandonFocusAndRestoreMode()
    }

    private fun requestRingtoneFocusAndMode() {
        val am = audioManager ?: return
        runCatching {
            if (previousAudioMode == null) {
                previousAudioMode = am.mode
            }
            am.mode = AudioManager.MODE_RINGTONE
        }
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

    private fun abandonFocusAndRestoreMode() {
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
        runCatching {
            if (am.mode == AudioManager.MODE_RINGTONE) {
                am.mode = previousAudioMode?.takeIf { it != AudioManager.MODE_RINGTONE }
                    ?: AudioManager.MODE_NORMAL
            }
            previousAudioMode = null
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0))
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
    fun startRinging(callId: String) {
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
        val shouldVibrate = ringerMode == AudioManager.RINGER_MODE_VIBRATE ||
            ringerMode == AudioManager.RINGER_MODE_NORMAL

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
