package com.mychattingpro.core.common

import android.content.Context
import java.io.File

object RetentionPolicy {
    const val AUTO_DELETE_DAYS = 20L
    const val AUTO_DELETE_MS = AUTO_DELETE_DAYS * 24L * 60L * 60L * 1000L

    private val CACHE_SUBDIRS = listOf(
        "video_cache",
        "voice_cache",
        "downloads",
        "temp_media",
        "image_cache",
        "video_thumbs"
    )

    fun cutoffMs(nowMs: Long = System.currentTimeMillis()): Long {
        return (nowMs - AUTO_DELETE_MS).coerceAtLeast(0L)
    }

    fun isExpired(timestampMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean {
        return timestampMs > 0L && timestampMs < cutoffMs(nowMs)
    }

    fun purgeExpiredLocalMediaCaches(
        context: Context,
        nowMs: Long = System.currentTimeMillis()
    ): Int {
        val cutoff = cutoffMs(nowMs)
        var deletedCount = 0
        for (dirName in CACHE_SUBDIRS) {
            deletedCount += purgeExpiredFilesInDirectory(File(context.cacheDir, dirName), cutoff)
        }
        deletedCount += purgeExpiredFilesInDirectory(File(context.filesDir, "voice"), cutoff)
        return deletedCount
    }

    internal fun purgeExpiredFilesInDirectory(dir: File, cutoffMs: Long): Int {
        if (!dir.exists() || !dir.isDirectory) return 0
        var deleted = 0
        val entries = dir.listFiles() ?: return 0
        for (entry in entries) {
            if (entry.isDirectory) {
                deleted += purgeExpiredFilesInDirectory(entry, cutoffMs)
                val remaining = entry.listFiles()
                if (remaining == null || remaining.isEmpty()) {
                    entry.delete()
                }
            } else if (entry.lastModified() in 1L until cutoffMs) {
                if (entry.delete()) {
                    deleted += 1
                }
            }
        }
        return deleted
    }
}
