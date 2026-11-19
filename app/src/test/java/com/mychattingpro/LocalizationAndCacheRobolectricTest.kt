package com.mychattingpro

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.R
import com.mychattingpro.core.common.CacheManager
import com.mychattingpro.core.localization.AppLanguage
import com.mychattingpro.core.localization.LocaleManager
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocalizationAndCacheRobolectricTest {

    @Test
    fun `localized resources exist for English, Bengali, and Arabic`() {
        val base = ApplicationProvider.getApplicationContext<Context>()

        val enCtx = LocaleManager.localizedContext(base, AppLanguage.ENGLISH)
        val bnCtx = LocaleManager.localizedContext(base, AppLanguage.BENGALI)
        val arCtx = LocaleManager.localizedContext(base, AppLanguage.ARABIC)

        assertEquals("No My Chatting Pro account found.", enCtx.getString(R.string.add_chat_not_found))
        assertEquals("TAWHID SHAKH", enCtx.getString(R.string.settings_owner_name))
        assertTrue(bnCtx.getString(R.string.nav_chats).isNotBlank())
        assertTrue(arCtx.getString(R.string.nav_chats).isNotBlank())
        assertEquals("সাবস্ক্রাইব করুন", enCtx.getString(R.string.settings_youtube_label))
        assertTrue(enCtx.getString(R.string.err_owner_access_denied).isNotBlank())
        assertTrue(bnCtx.getString(R.string.err_owner_access_denied).isNotBlank())
        assertTrue(arCtx.getString(R.string.err_owner_access_denied).isNotBlank())
        assertTrue(bnCtx.getString(R.string.owner_load_more).isNotBlank())
    }

    @Test
    fun `clearTemporaryCache only removes temp cache dirs and leaves databases intact`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tempMedia = File(context.cacheDir, "temp_media").apply { mkdirs() }
        val dummyThumb = File(tempMedia, "thumb_1.jpg").apply { writeText("cached_bytes") }
        val dbDir = File(context.filesDir, "user_important_data").apply { mkdirs() }
        val importantFile = File(dbDir, "keep.txt").apply { writeText("do_not_delete") }

        val cleared = CacheManager.clearTemporaryCache(context)

        assertTrue(cleared >= "cached_bytes".length)
        assertFalse(dummyThumb.exists())
        assertTrue(importantFile.exists())
    }
}
