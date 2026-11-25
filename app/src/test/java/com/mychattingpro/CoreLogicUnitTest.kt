package com.mychattingpro

import com.mychattingpro.core.common.AppError
import com.mychattingpro.core.common.CountryCatalog
import com.mychattingpro.core.localization.AppLanguage
import com.mychattingpro.core.localization.DateTimeFormatters
import com.mychattingpro.data.repository.AuthRepositoryImpl
import com.mychattingpro.data.repository.ErrorMapper
import com.mychattingpro.domain.models.CallHistoryItem
import com.mychattingpro.domain.models.IceServerConfig
import com.mychattingpro.domain.models.Message
import com.mychattingpro.domain.models.MessageStatus
import com.mychattingpro.domain.models.MessageType
import com.mychattingpro.features.ads.InterstitialAdManager
import com.mychattingpro.features.calls.IceServerSanitizer
import com.mychattingpro.features.messages.ChatTimelineItem
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

class CoreLogicUnitTest {

    @Test
    fun `country catalog normalizes international phone numbers to E164`() {
        val bd = CountryCatalog.findByIso("BD")
        val us = CountryCatalog.findByIso("US")
        val sa = CountryCatalog.findByIso("SA")

        assertEquals("+8801712345678", CountryCatalog.normalizeToE164(bd, "01712345678"))
        assertEquals("+8801712345678", CountryCatalog.normalizeToE164(bd, "+880 1712-345678"))
        assertEquals("+16505550123", CountryCatalog.normalizeToE164(us, "650-555-0123"))
        assertEquals("+966501234567", CountryCatalog.normalizeToE164(sa, "0501234567"))
        assertNull(CountryCatalog.normalizeToE164(bd, "123"))
        assertNull(CountryCatalog.normalizeToE164(us, ""))
    }

    @Test
    fun `country catalog includes wide international support`() {
        assertTrue(CountryCatalog.allCountries.size > 150)
        assertTrue(CountryCatalog.popularIsoCodes.contains("BD"))
        assertTrue(CountryCatalog.popularIsoCodes.contains("US"))
        assertTrue(CountryCatalog.popularIsoCodes.contains("SA"))
    }

    @Test
    fun `app languages preserve RTL and native labels`() {
        assertEquals("বাংলা", AppLanguage.BENGALI.nativeLabel)
        assertFalse(AppLanguage.BENGALI.isRtl)

        assertEquals("English", AppLanguage.ENGLISH.nativeLabel)
        assertFalse(AppLanguage.ENGLISH.isRtl)

        assertEquals("العربية", AppLanguage.ARABIC.nativeLabel)
        assertTrue(AppLanguage.ARABIC.isRtl)
    }

    @Test
    fun `date formatter formats duration cleanly`() {
        assertEquals("00:00", DateTimeFormatters.formatDuration(0))
        assertEquals("01:05", DateTimeFormatters.formatDuration(65))
        assertEquals("12:30", DateTimeFormatters.formatDuration(750))
        assertNotNull(DateTimeFormatters.formatTimeOnly(System.currentTimeMillis(), AppLanguage.ENGLISH))
    }

    @Test
    fun `error mapper maps backend codes including owner access denied cleanly`() {
        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        val mapper = ErrorMapper(moshi)

        assertTrue(mapper.map(IOException("offline")) is AppError.NetworkUnavailable)

        val errJson = """{"error":"wrong_pin","message":"Invalid PIN"}"""
        val httpEx = HttpException(
            Response.error<Any>(401, errJson.toResponseBody("application/json".toMediaType()))
        )
        assertTrue(mapper.map(httpEx) is AppError.WrongPin)

        val rateJson = """{"error":"rate_limited","retryAfterSeconds":45}"""
        val rateEx = HttpException(
            Response.error<Any>(429, rateJson.toResponseBody("application/json".toMediaType()))
        )
        val mappedRate = mapper.map(rateEx)
        assertTrue(mappedRate is AppError.RateLimited)
        assertEquals(45, (mappedRate as AppError.RateLimited).retryAfterSeconds)

        val ownerKeyJson = """{"error":"invalid_owner_key","message":"Bad key"}"""
        val ownerEx = HttpException(
            Response.error<Any>(403, ownerKeyJson.toResponseBody("application/json".toMediaType()))
        )
        assertTrue(mapper.map(ownerEx) is AppError.InvalidOwnerKey)

        val ownerDeniedJson = """{"error":"owner_access_denied","message":"Not owner"}"""
        val ownerDeniedEx = HttpException(
            Response.error<Any>(403, ownerDeniedJson.toResponseBody("application/json".toMediaType()))
        )
        assertTrue(mapper.map(ownerDeniedEx) is AppError.OwnerAccessDenied)

        val ownerMissingJson = """{"error":"owner_monitor_not_configured","message":"Not set"}"""
        val ownerMissingEx = HttpException(
            Response.error<Any>(503, ownerMissingJson.toResponseBody("application/json".toMediaType()))
        )
        assertTrue(mapper.map(ownerMissingEx) is AppError.OwnerMonitorNotConfigured)
    }

    @Test
    fun `interstitial ad manager separates debug test id from release production id and enforces cooldown`() {
        assertEquals(
            "ca-app-pub-3940256099942544/1033173712",
            InterstitialAdManager.resolveAdUnitId(isDebug = true)
        )
        assertEquals(
            "ca-app-pub-9716395280477689/4017609898",
            InterstitialAdManager.resolveAdUnitId(isDebug = false)
        )

        assertFalse(InterstitialAdManager.isCooldownActive(nowMs = 10_000L, lastShownAtMs = 0L))
        assertTrue(InterstitialAdManager.isCooldownActive(nowMs = 25_000L, lastShownAtMs = 10_000L))
        assertFalse(InterstitialAdManager.isCooldownActive(nowMs = 35_000L, lastShownAtMs = 10_000L))

        assertEquals(5_000L, InterstitialAdManager.retryDelayMs(0))
        assertEquals(15_000L, InterstitialAdManager.retryDelayMs(1))
        assertEquals(30_000L, InterstitialAdManager.retryDelayMs(2))
        assertEquals(60_000L, InterstitialAdManager.retryDelayMs(3))
        assertEquals(120_000L, InterstitialAdManager.retryDelayMs(4))
        assertEquals(120_000L, InterstitialAdManager.retryDelayMs(10))

        assertFalse(InterstitialAdManager.isLoadedAdExpired(nowMs = 100_000L, loadedAtMs = 0L))
        assertFalse(InterstitialAdManager.isLoadedAdExpired(nowMs = 1_000_000L, loadedAtMs = 500_000L))
        assertTrue(
            InterstitialAdManager.isLoadedAdExpired(
                nowMs = 500_000L + InterstitialAdManager.MAX_AD_AGE_MS,
                loadedAtMs = 500_000L
            )
        )
    }

    @Test
    fun `ice server sanitizer repairs bare turn hosts rejects https and preserves default stun servers`() {
        assertEquals("turn:relay.metered.ca:80", IceServerSanitizer.normalizeIceUrl("relay.metered.ca:80"))
        assertEquals("turns:global.relay.metered.ca:443?transport=tcp", IceServerSanitizer.normalizeIceUrl("global.relay.metered.ca:443?transport=tcp"))
        assertEquals("turn:a.relay.metered.ca:80", IceServerSanitizer.normalizeIceUrl("\"turn:a.relay.metered.ca:80\""))
        assertNull(IceServerSanitizer.normalizeIceUrl("https://relay.metered.ca"))
        assertNull(IceServerSanitizer.normalizeIceUrl("invalid url with spaces"))

        val sanitized = IceServerSanitizer.sanitize(
            listOf(
                IceServerConfig(
                    urls = listOf(
                        "https://bad.example.com",
                        "relay.metered.ca:80, turns:relay.metered.ca:443?transport=tcp"
                    ),
                    username = " u1 ",
                    credential = " p1 "
                )
            )
        )

        assertTrue(sanitized.first().urls.containsAll(IceServerSanitizer.DEFAULT_STUN_URLS))
        assertEquals(2, sanitized.size)
        assertEquals(
            listOf("turn:relay.metered.ca:80", "turns:relay.metered.ca:443?transport=tcp"),
            sanitized[1].urls
        )
        assertEquals("u1", sanitized[1].username)
        assertEquals("p1", sanitized[1].credential)
    }

    @Test
    fun `bubble aspect ratio preserves portrait and landscape bounds cleanly`() {
        assertNull(com.mychattingpro.features.media.MediaUploadHelper.computeBubbleAspectRatio(null, 100))
        assertNull(com.mychattingpro.features.media.MediaUploadHelper.computeBubbleAspectRatio(0, 100))
        assertEquals(0.6f, com.mychattingpro.features.media.MediaUploadHelper.computeBubbleAspectRatio(300, 1200)!!, 0.001f)
        assertEquals(1.9f, com.mychattingpro.features.media.MediaUploadHelper.computeBubbleAspectRatio(1920, 400)!!, 0.001f)
        assertEquals(1.333f, com.mychattingpro.features.media.MediaUploadHelper.computeBubbleAspectRatio(1600, 1200)!!, 0.01f)
    }

    @Test
    fun `error mapper maps user_blocked backend code to UserBlocked`() {
        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        val mapper = ErrorMapper(moshi)
        val blockedJson = """{"error":"user_blocked","message":"Messaging unavailable"}"""
        val ex = HttpException(
            Response.error<Any>(403, blockedJson.toResponseBody("application/json".toMediaType()))
        )
        assertTrue(mapper.map(ex) is AppError.UserBlocked)
    }

    @Test
    fun `device name formatter capitalizes and deduplicates brand and model`() {
        assertEquals("Samsung SM-A525F", AuthRepositoryImpl.formatDeviceName("samsung", "SM-A525F"))
        assertEquals("Xiaomi Redmi 12", AuthRepositoryImpl.formatDeviceName("xiaomi", "Xiaomi Redmi 12"))
        assertEquals("OnePlus CPH2449", AuthRepositoryImpl.formatDeviceName("OnePlus", "CPH2449"))
    }

    @Test
    fun `chat timeline builder merges messages and peer calls chronologically`() {
        val msg1 = Message(
            id = "m1",
            conversationId = "c1",
            senderId = "u1",
            clientMessageId = "cm1",
            type = MessageType.TEXT,
            bodyText = "Hello",
            media = null,
            status = MessageStatus.READ,
            createdAtMs = 1000L
        )
        val call1 = CallHistoryItem(
            callId = "call1",
            conversationId = "c1",
            callerId = "u2",
            peerUserId = "u2",
            peerDisplayName = "Peer",
            peerAvatarMediaId = null,
            isVideo = true,
            status = "missed",
            direction = "incoming",
            startedAtMs = 2000L,
            answeredAtMs = null,
            endedAtMs = 2020L,
            durationSeconds = 0
        )
        val msg2 = msg1.copy(id = "m2", clientMessageId = "cm2", bodyText = "Call me back", createdAtMs = 3000L)

        val timeline = ChatTimelineItem.buildTimeline(listOf(msg2, msg1), listOf(call1))
        assertEquals(3, timeline.size)
        assertTrue(timeline[0] is ChatTimelineItem.MessageEntry)
        assertEquals("msg_m1", timeline[0].timelineId)
        assertTrue(timeline[1] is ChatTimelineItem.CallEntry)
        assertEquals("call_call1", timeline[1].timelineId)
        assertTrue(timeline[2] is ChatTimelineItem.MessageEntry)
        assertEquals("msg_m2", timeline[2].timelineId)
    }
}
