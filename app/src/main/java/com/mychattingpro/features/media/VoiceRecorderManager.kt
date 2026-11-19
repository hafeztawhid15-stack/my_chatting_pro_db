package com.mychattingpro.features.media

import android.content.Context
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

    fun startRecording() {
        stopPlayback()
        cleanupRecorder()

        val dir = File(context.filesDir, "voice").apply { mkdirs() }
        val file = File(dir, "voice_${UUID.randomUUID()}.m4a")

        try {
            val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            rec.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(96_000)
                setAudioSamplingRate(44_100)
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
        } catch (e: Exception) {
            cleanupRecorder()
            file.delete()
            _state.value = VoiceRecordingState(error = e.localizedMessage ?: "Recording failed")
        }
    }

    fun stopRecording(): Pair<File, Long>? {
        val currentFile = _state.value.recordedFile
        val elapsed = (System.currentTimeMillis() - startTimeMs).coerceAtLeast(500L)
        timerJob?.cancel()
        runCatching {
            recorder?.stop()
        }
        cleanupRecorder()
        _state.value = _state.value.copy(
            isRecording = false,
            elapsedMs = elapsed,
            amplitude = 0f
        )
        return if (currentFile != null && currentFile.exists() && currentFile.length() > 0) {
            currentFile to elapsed
        } else {
            null
        }
    }

    fun cancelRecording() {
        timerJob?.cancel()
        runCatching { recorder?.stop() }
        cleanupRecorder()
        stopPlayback()
        _state.value.recordedFile?.delete()
        _state.value = VoiceRecordingState()
    }

    fun togglePreviewPlayback() {
        val file = _state.value.recordedFile ?: return
        if (_state.value.isPreviewPlaying) {
            stopPlayback()
            return
        }
        runCatching {
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                setOnCompletionListener {
                    _state.value = _state.value.copy(isPreviewPlaying = false)
                }
                start()
            }
            _state.value = _state.value.copy(isPreviewPlaying = true)
        }.onFailure {
            stopPlayback()
        }
    }

    fun stopPlayback() {
        runCatching {
            player?.stop()
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
