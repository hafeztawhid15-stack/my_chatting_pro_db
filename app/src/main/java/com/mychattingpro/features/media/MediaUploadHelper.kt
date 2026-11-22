package com.mychattingpro.features.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
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

        val orientation = runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        } ?: throw IOException("Unable to open selected image stream")

        var sampleSize = 1
        while (bounds.outWidth > 0 && bounds.outHeight > 0 &&
            (bounds.outWidth / sampleSize > maxDimension || bounds.outHeight / sampleSize > maxDimension)
        ) {
            sampleSize *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val decoded = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOpts)
        } ?: throw IOException("Unable to decode selected image")

        val orientedBitmap = applyExifOrientation(decoded, orientation)

        try {
            FileOutputStream(outFile).use { fos ->
                if (!orientedBitmap.compress(Bitmap.CompressFormat.JPEG, quality, fos)) {
                    throw IOException("Unable to compress selected image")
                }
            }
        } catch (t: Throwable) {
            outFile.delete()
            throw t
        } finally {
            if (orientedBitmap !== decoded) {
                decoded.recycle()
            }
            orientedBitmap.recycle()
        }

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
        val displayName = queryDisplayName(context, uri) ?: "attachment_${UUID.randomUUID()}.$fallbackExtension"
        val mimeType = inferMimeType(context, uri, displayName, fallbackExtension)
        val tempDir = File(context.cacheDir, "temp_media").apply { mkdirs() }
        val safeTempName = "upload_${UUID.randomUUID()}_${displayName.replace(Regex("[^a-zA-Z0-9._-]"), "_")}"
        val outFile = File(tempDir, safeTempName)

        try {
            val stream = resolver.openInputStream(uri) ?: throw IOException("Cannot read selected file")
            stream.use { input ->
                FileOutputStream(outFile).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                    }
                }
            }
            if (outFile.length() <= 0L) {
                throw IOException("Selected file is empty")
            }
        } catch (t: Throwable) {
            outFile.delete()
            throw t
        }

        PreparedMediaFile(
            file = outFile,
            filename = displayName,
            mimeType = mimeType,
            sizeBytes = outFile.length()
        )
    }

    suspend fun saveImageToGallery(
        context: Context,
        imageBytes: ByteArray,
        preferredFilename: String,
        mimeType: String = "image/jpeg"
    ): Uri = withContext(Dispatchers.IO) {
        val safeName = preferredFilename.ifBlank { "MCP_${System.currentTimeMillis()}.jpg" }
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, safeName)
                put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/MY CHATTING PRO")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val itemUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Unable to create MediaStore entry")
            try {
                resolver.openOutputStream(itemUri)?.use { out ->
                    out.write(imageBytes)
                } ?: throw IOException("Unable to open gallery output stream")
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(itemUri, values, null, null)
                itemUri
            } catch (t: Throwable) {
                resolver.delete(itemUri, null, null)
                throw t
            }
        } else {
            // Zero-permission fallback on API 24-28 using app-specific Pictures directory
            val picturesDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                ?: File(context.filesDir, "saved_pictures")
            picturesDir.mkdirs()
            val outFile = File(picturesDir, safeName)
            FileOutputStream(outFile).use { it.write(imageBytes) }
            Uri.fromFile(outFile)
        }
    }

    private fun inferMimeType(
        context: Context,
        uri: Uri,
        displayName: String,
        fallbackExtension: String
    ): String {
        val resolved = context.contentResolver.getType(uri)
        if (!resolved.isNullOrBlank() && resolved != "application/octet-stream") {
            return resolved
        }
        val ext = displayName.substringAfterLast('.', fallbackExtension).lowercase()
        return when (ext) {
            "m4a" -> "audio/mp4"
            "aac" -> "audio/aac"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            "amr" -> "audio/amr"
            "3gp" -> "audio/3gpp"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "mp4" -> "video/mp4"
            "pdf" -> "application/pdf"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
    }

    private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            else -> return bitmap
        }
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrDefault(bitmap)
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
