package com.mychattingpro.core.network

import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.File
import java.io.FileInputStream

/**
 * Streaming RequestBody that avoids loading large media files into RAM
 * and emits exact byte-level upload progress [0f..1f].
 */
class ProgressRequestBody(
    private val file: File,
    private val contentType: MediaType?,
    private val onProgress: (Float) -> Unit
) : RequestBody() {

    override fun contentType(): MediaType? = contentType

    override fun contentLength(): Long = if (file.exists()) file.length() else -1L

    override fun writeTo(sink: BufferedSink) {
        if (!file.exists()) {
            throw java.io.FileNotFoundException("Upload file does not exist")
        }
        val total = file.length().coerceAtLeast(1L)
        val buffer = ByteArray(16 * 1024)
        var uploaded = 0L
        var lastReported = -1f
        FileInputStream(file).use { input ->
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                sink.write(buffer, 0, read)
                uploaded += read
                val progress = (uploaded.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                if (progress >= 1f || progress - lastReported >= 0.02f) {
                    lastReported = progress
                    onProgress(progress)
                }
            }
        }
    }
}
