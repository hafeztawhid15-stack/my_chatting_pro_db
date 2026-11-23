package com.mychattingpro.features.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.util.UUID

data class VoiceRecordingState(
    val isRecording: Boolean = false,
    val elapsedMs: Long = 0L,
    val amplitude: Float = 0f,
    val recordedFile: File? = null,
    val isPreviewPlaying: Boolean = false,
    val error: String? = null
)

class VoiceRecorderManager(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.Main)
    private var recorder: MediaRecorder? = null
    private var player: MediaPlayer? = null
    private var timerJob: Job? = null
    private var startTimeMs: Long = 0L

    private val _state = MutableStateFlow(VoiceRecordingState())
    val state: StateFlow<VoiceRecordingState> = _state.asStateFlow()

    private data class RecorderProfile(
        val audioSource: Int,
        val outputFormat: Int,
        val audioEncoder: Int,
        val bitRate: Int?,
        val sampleRate: Int?,
        val extension: String
    )

    fun startRecording() {
        stopPlayback()
        cleanupRecorder()

        val dir = File(context.filesDir, "voice").apply { mkdirs() }
        val profiles = listOf(
            RecorderProfile(
                audioSource = MediaRecorder.AudioSource.MIC,
                outputFormat = MediaRecorder.OutputFormat.MPEG_4,
                audioEncoder = MediaRecorder.AudioEncoder.AAC,
                bitRate = 96_000,
                sampleRate = 44_100,
                extension = "m4a"
            ),
            RecorderProfile(
                audioSource = MediaRecorder.AudioSource.DEFAULT,
                outputFormat = MediaRecorder.OutputFormat.MPEG_4,
                audioEncoder = MediaRecorder.AudioEncoder.AAC,
                bitRate = 48_000,
                sampleRate = 16_000,
                extension = "m4a"
            ),
            RecorderProfile(
                audioSource = MediaRecorder.AudioSource.DEFAULT,
                outputFormat = MediaRecorder.OutputFormat.THREE_GPP,
                audioEncoder = MediaRecorder.AudioEncoder.AMR_NB,
                bitRate = null,
                sampleRate = null,
                extension = "3gp"
            )
        )

        var lastError: Exception? = null
        for (profile in profiles) {
            val file = File(dir, "voice_${UUID.randomUUID()}.${profile.extension}")
            try {
                val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    MediaRecorder(context)
                } else {
                    @Suppress("DEPRECATION")
                    MediaRecorder()
                }
                rec.apply {
                    setAudioSource(profile.audioSource)
                    setOutputFormat(profile.outputFormat)
                    setAudioEncoder(profile.audioEncoder)
                    profile.bitRate?.let { setAudioEncodingBitRate(it) }
                    profile.sampleRate?.let { setAudioSamplingRate(it) }
                    setOutputFile(file.absolutePath)
                    prepare()
                    start()
                }
                recorder = rec
                startTimeMs = System.currentTimeMillis()
                _state.value = VoiceRecordingState(isRecording = true, recordedFile = file)

                timerJob = scope.launch {
                    while (isActive && _state.value.isRecording) {
                        val amp = runCatching { (recorder?.maxAmplitude ?: 0) / 32767f }.getOrDefault(0f)
                        _state.value = _state.value.copy(
                            elapsedMs = System.currentTimeMillis() - startTimeMs,
                            amplitude = amp.coerceIn(0f, 1f)
                        )
                        delay(100)
                    }
                }
                return
            } catch (e: Exception) {
                lastError = e
                cleanupRecorder()
                file.delete()
            }
        }

        _state.value = VoiceRecordingState(
            error = lastError?.localizedMessage ?: "Unable to start microphone recording."
        )
    }

    fun stopRecording(): Pair<File, Long>? {
        val currentState = _state.value
        val currentFile = currentState.recordedFile

        // Already stopped earlier (e.g. user clicked Stop, previewed, and clicked Send)
        if (!currentState.isRecording) {
            return if (currentFile != null && currentFile.exists() && currentFile.length() > 0L) {
                currentFile to currentState.elapsedMs.coerceAtLeast(500L)
            } else {
                null
            }
        }

        val elapsed = (System.currentTimeMillis() - startTimeMs).coerceAtLeast(500L)
        timerJob?.cancel()
        val stopResult = runCatching {
            recorder?.stop()
        }
        cleanupRecorder()

        if (stopResult.isFailure || currentFile == null || !currentFile.exists() || currentFile.length() <= 0L) {
            currentFile?.delete()
            _state.value = VoiceRecordingState(
                error = "Recording too short. Record for at least 1 second and try again."
            )
            return null
        }

        _state.value = _state.value.copy(
            isRecording = false,
            elapsedMs = elapsed,
            amplitude = 0f,
            error = null
        )
        return currentFile to elapsed
    }

    fun cancelRecording() {
        timerJob?.cancel()
        runCatching { recorder?.stop() }
        cleanupRecorder()
        stopPlayback()
        _state.value.recordedFile?.delete()
        _state.value = VoiceRecordingState()
    }

    fun clearRecordedStateWithoutDeleting() {
        timerJob?.cancel()
        cleanupRecorder()
        stopPlayback()
        _state.value = VoiceRecordingState()
    }

    fun clearError() {
        if (_state.value.error != null) {
            _state.value = _state.value.copy(error = null)
        }
    }

    fun togglePreviewPlayback() {
        val file = _state.value.recordedFile ?: return
        if (_state.value.isPreviewPlaying) {
            stopPlayback()
            return
        }
        runCatching {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (am != null && am.mode != AudioManager.MODE_IN_COMMUNICATION) {
                am.mode = AudioManager.MODE_NORMAL
            }
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            player = MediaPlayer().apply {
                setAudioAttributes(attrs)
                FileInputStream(file).use { fis ->
                    setDataSource(fis.fd)
                }
                setVolume(1.0f, 1.0f)
                prepare()
                setOnCompletionListener {
                    stopPlayback()
                }
                setOnErrorListener { _, _, _ ->
                    stopPlayback()
                    _state.value = _state.value.copy(error = "Unable to play recorded voice preview.")
                    true
                }
                start()
            }
            _state.value = _state.value.copy(isPreviewPlaying = true)
        }.onFailure {
            stopPlayback()
            _state.value = _state.value.copy(error = "Unable to play recorded voice preview.")
        }
    }

    fun stopPlayback() {
        runCatching {
            if (player?.isPlaying == true) {
                player?.stop()
            }
            player?.release()
        }
        player = null
        _state.value = _state.value.copy(isPreviewPlaying = false)
    }

    fun release() {
        timerJob?.cancel()
        cleanupRecorder()
        stopPlayback()
    }

    private fun cleanupRecorder() {
        runCatching { recorder?.release() }
        recorder = null
    }
}
