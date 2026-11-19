package com.mychattingpro.features.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class PreparedMediaFile(
    val file: File,
    val filename: String,
    val mimeType: String,
    val sizeBytes: Long
)

object MediaUploadHelper {

    const val MAX_UPLOAD_BYTES: Long = 50L * 1024L * 1024L
    private val dangerousExtensions = setOf("apk", "exe", "bat", "cmd", "sh", "msi", "zip", "rar")

    fun isDangerousFile(filename: String): Boolean {
        val ext = filename.substringAfterLast('.', "").lowercase()
        return ext in dangerousExtensions
    }

    fun formatFileSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format("%.1f MB", mb)
        return String.format("%.2f GB", mb / 1024.0)
    }

    suspend fun prepareImageForUpload(
        context: Context,
        uri: Uri,
        maxDimension: Int = 1600,
        quality: Int = 82
    ): PreparedMediaFile = withContext(Dispatchers.IO) {
        val originalName = queryDisplayName(context, uri) ?: "photo_${UUID.randomUUID()}.jpg"
        val tempDir = File(context.cacheDir, "temp_media").apply { mkdirs() }
        val outFile = File(tempDir, "img_${UUID.randomUUID()}.jpg")

        // Decode bounds first to prevent OOM on high-megapixel photos
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        }

        var sampleSize = 1
        while (bounds.outWidth / sampleSize > maxDimension || bounds.outHeight / sampleSize > maxDimension) {
            sampleSize *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOpts)
        } ?: error("Unable to decode selected image")

        FileOutputStream(outFile).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, fos)
        }
        bitmap.recycle()

        val jpegName = originalName.substringBeforeLast('.') + ".jpg"
        PreparedMediaFile(
            file = outFile,
            filename = jpegName,
            mimeType = "image/jpeg",
            sizeBytes = outFile.length()
        )
    }

    suspend fun copyUriToTempFile(
        context: Context,
        uri: Uri,
        fallbackExtension: String = "bin"
    ): PreparedMediaFile = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val mimeType = resolver.getType(uri) ?: "application/octet-stream"
        val displayName = queryDisplayName(context, uri) ?: "attachment_${UUID.randomUUID()}.$fallbackExtension"
        val tempDir = File(context.cacheDir, "temp_media").apply { mkdirs() }
        val safeTempName = "upload_${UUID.randomUUID()}_${displayName.replace(Regex("[^a-zA-Z0-9._-]"), "_")}"
        val outFile = File(tempDir, safeTempName)

        resolver.openInputStream(uri)?.use { input ->
            FileOutputStream(outFile).use { output ->
                val buffer = ByteArray(16 * 1024)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                }
            }
        } ?: error("Cannot read selected file")

        PreparedMediaFile(
            file = outFile,
            filename = displayName,
            mimeType = mimeType,
            sizeBytes = outFile.length()
        )
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") {
            return uri.lastPathSegment
        }
        return context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) cursor.getString(idx) else null
            } else {
                null
            }
        }
    }
}
