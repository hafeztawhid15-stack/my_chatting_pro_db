package com.mychattingpro.core.common

import android.content.Context
import coil.imageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Safely clears ONLY temporary thumbnails, Coil image cache, and temporary downloaded previews.
 * NEVER deletes Room chat history, user messages, saved media, or authentication tokens.
 */
object CacheManager {

    suspend fun clearTemporaryCache(context: Context): Long = withContext(Dispatchers.IO) {
        var clearedBytes = 0L

        // 1. Clear Coil memory + disk cache
        runCatching {
            val loader = context.imageLoader
            loader.memoryCache?.clear()
            loader.diskCache?.clear()
        }

        // 2. Clear temporary media & thumbnail subdirectories in app cacheDir ONLY
        val tempDirs = listOf(
            File(context.cacheDir, "temp_media"),
            File(context.cacheDir, "downloads"),
            File(context.cacheDir, "image_cache"),
            File(context.cacheDir, "video_thumbs")
        )

        for (dir in tempDirs) {
            if (dir.exists()) {
                clearedBytes += deleteRecursivelySafe(dir)
            }
        }

        clearedBytes
    }

    private fun deleteRecursivelySafe(target: File): Long {
        var bytes = 0L
        if (target.isDirectory) {
            target.listFiles()?.forEach { child ->
                bytes += deleteRecursivelySafe(child)
            }
        } else if (target.isFile) {
            bytes += target.length()
            target.delete()
        }
        return bytes
    }
}
