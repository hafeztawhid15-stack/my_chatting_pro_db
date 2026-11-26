package com.mychattingpro

import com.mychattingpro.core.common.RetentionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RetentionPolicyUnitTest {

    @Test
    fun cutoffUsesExactTwentyDaysWindow() {
        val nowMs = 1_800_000_000_000L
        assertEquals(20L, RetentionPolicy.AUTO_DELETE_DAYS)
        assertEquals(1_728_000_000L, RetentionPolicy.AUTO_DELETE_MS)
        assertEquals(nowMs - 1_728_000_000L, RetentionPolicy.cutoffMs(nowMs))
    }

    @Test
    fun isExpiredRespectsTwentyDayBoundary() {
        val nowMs = 1_800_000_000_000L
        val cutoff = RetentionPolicy.cutoffMs(nowMs)

        // Exactly at cutoff or newer (e.g., 19 days 23 hours) -> NOT expired
        assertFalse(RetentionPolicy.isExpired(cutoff, nowMs))
        assertFalse(RetentionPolicy.isExpired(cutoff + 3_600_000L, nowMs))
        assertFalse(RetentionPolicy.isExpired(0L, nowMs))

        // Strictly older than 20 days -> expired
        assertTrue(RetentionPolicy.isExpired(cutoff - 1L, nowMs))
        assertTrue(RetentionPolicy.isExpired(cutoff - 86_400_000L, nowMs))
    }

    @Test
    fun purgeExpiredFilesInDirectoryDeletesOnlyFilesOlderThanTwentyDays() {
        val tempRoot = Files.createTempDirectory("retention_cache_test").toFile()
        try {
            val nowMs = 1_800_000_000_000L
            val cutoff = RetentionPolicy.cutoffMs(nowMs)

            val expiredPhoto = File(tempRoot, "old_img.jpg").apply {
                writeText("old")
                setLastModified(cutoff - 60_000L)
            }
            val nestedExpiredDir = File(tempRoot, "nested").apply { mkdirs() }
            val expiredVideo = File(nestedExpiredDir, "old_vid.mp4").apply {
                writeText("old-vid")
                setLastModified(cutoff - 10_000L)
            }
            val freshImage = File(tempRoot, "fresh_img.jpg").apply {
                writeText("fresh")
                setLastModified(cutoff + 60_000L)
            }

            val deletedCount = RetentionPolicy.purgeExpiredFilesInDirectory(tempRoot, cutoff)

            assertEquals(2, deletedCount)
            assertFalse(expiredPhoto.exists())
            assertFalse(expiredVideo.exists())
            assertFalse(nestedExpiredDir.exists())
            assertTrue(freshImage.exists())
        } finally {
            tempRoot.deleteRecursively()
        }
    }
}
