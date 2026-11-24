package com.mychattingpro

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.BuildConfig
import com.mychattingpro.core.common.AppError
import com.mychattingpro.core.database.MyChattingProDatabase
import com.mychattingpro.core.datastore.AppPreferencesDataStore
import com.mychattingpro.core.network.ApiErrorResponseDto
import com.mychattingpro.core.network.CloudflareApiService
import com.mychattingpro.core.network.NetworkMonitor
import com.mychattingpro.core.network.RealtimeSocketManager
import com.mychattingpro.core.security.EncryptedTokenStore
import com.mychattingpro.core.security.PrivateChatLockManager
import com.mychattingpro.data.repository.AppException
import com.mychattingpro.data.repository.AuthRepositoryImpl
import com.mychattingpro.data.repository.ErrorMapper
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.HttpException
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.File
import java.io.FileNotFoundException
import java.net.SocketTimeoutException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CompleteUpdateRobolectricTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var app: Application

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        app = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `error mapper differentiates timeout, storage access, recovery code, report, block, and session revoked`() {
        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        val mapper = ErrorMapper(moshi)

        assertTrue(mapper.map(SocketTimeoutException("timed out")) is AppError.TimeoutError)
        assertTrue(mapper.map(FileNotFoundException("missing")) is AppError.StorageFailure)

        fun httpError(code: Int, errorKey: String): HttpException {
            val json = """{"error":"$errorKey","message":"$errorKey"}"""
            val body = json.toResponseBody("application/json".toMediaType())
            return HttpException(Response.error<Any>(code, body))
        }

        assertTrue(mapper.map(httpError(403, "storage_permission_denied")) is AppError.StoragePermissionDenied)
        assertTrue(mapper.map(httpError(500, "storage_bucket_missing")) is AppError.StorageBucketMissing)
        assertTrue(mapper.map(httpError(401, "invalid_recovery_code")) is AppError.InvalidRecoveryCode)
        assertTrue(mapper.map(httpError(409, "recovery_code_not_set")) is AppError.RecoveryCodeNotSet)
        assertTrue(mapper.map(httpError(400, "invalid_delete_confirmation")) is AppError.InvalidDeleteConfirmation)
        assertTrue(mapper.map(httpError(409, "duplicate_report")) is AppError.DuplicateReport)
        assertTrue(mapper.map(httpError(401, "session_revoked")) is AppError.SessionRevoked)
        assertTrue(mapper.map(httpError(403, "user_blocked")) is AppError.UserBlocked)
    }

    @Test
    fun `build config version name and code are valid for dynamic version display`() {
        assertTrue(BuildConfig.VERSION_NAME.isNotBlank())
        assertTrue(BuildConfig.VERSION_CODE > 0)
        assertTrue(BuildConfig.APPLICATION_ID.isNotBlank())
    }

    @Test
    fun `auth repository validates DELETE confirmation and recovery code before network`() = runTest {
        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        val okHttp = OkHttpClient.Builder().build()
        val api = Retrofit.Builder()
            .baseUrl("https://example.invalid/")
            .client(okHttp)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(CloudflareApiService::class.java)
        val db = MyChattingProDatabase.getInstance(app)
        val prefs = AppPreferencesDataStore(app)
        val tokenStore = EncryptedTokenStore(app)
        val lockManager = PrivateChatLockManager()
        val socket = RealtimeSocketManager("https://example.invalid", okHttp, tokenStore, prefs, NetworkMonitor(app))

        val repo = AuthRepositoryImpl(
            context = app,
            api = api,
            tokenStore = tokenStore,
            prefsStore = prefs,
            conversationDao = db.conversationDao(),
            messageDao = db.messageDao(),
            callHistoryDao = db.callHistoryDao(),
            socketManager = socket,
            errorMapper = ErrorMapper(moshi),
            privateChatLockManager = lockManager
        )

        val wrongConfirm = repo.deleteAccount(pin = "12345", confirmText = "confirm")
        assertTrue(wrongConfirm.isFailure)
        assertEquals(
            AppError.InvalidDeleteConfirmation,
            (wrongConfirm.exceptionOrNull() as? AppException)?.appError
        )

        val shortCode = repo.resetLoginPinWithRecoveryCode(
            phoneE164 = "+8801712345678",
            recoveryCode = "ABC",
            newPin = "54321",
            confirmNewPin = "54321"
        )
        assertTrue(shortCode.isFailure)
        assertEquals(
            AppError.InvalidRecoveryCode,
            (shortCode.exceptionOrNull() as? AppException)?.appError
        )
    }

    @Test
    fun `datastore persists all 14 notification settings and UnifiedPush config`() = runTest {
        val prefs = AppPreferencesDataStore(app)
        prefs.setNotificationsEnabled(false)
        prefs.setMessageNotificationsEnabled(false)
        prefs.setPrivateChatNotificationsEnabled(false)
        prefs.setShowMessagePreview(false)
        prefs.setMessageSoundEnabled(false)
        prefs.setVibrationEnabled(false)
        prefs.setInAppAlertsEnabled(false)
        prefs.setCallNotificationsEnabled(false)
        prefs.setMissedCallNotificationsEnabled(false)
        prefs.setCallVibrationEnabled(false)
        prefs.setSecurityAlertsEnabled(false)
        prefs.setAccountAlertsEnabled(false)
        prefs.setBackgroundSyncEnabled(false)
        prefs.setUnifiedPushConfig(enabled = true, endpointUrl = "https://ntfy.sh/mcp-test-topic")

        val saved = prefs.current()
        assertFalse(saved.notificationsEnabled)
        assertFalse(saved.messageNotificationsEnabled)
        assertFalse(saved.privateChatNotificationsEnabled)
        assertFalse(saved.showMessagePreview)
        assertFalse(saved.messageSoundEnabled)
        assertFalse(saved.vibrationEnabled)
        assertFalse(saved.inAppAlertsEnabled)
        assertFalse(saved.callNotificationsEnabled)
        assertFalse(saved.missedCallNotificationsEnabled)
        assertFalse(saved.callVibrationEnabled)
        assertFalse(saved.securityAlertsEnabled)
        assertFalse(saved.accountAlertsEnabled)
        assertFalse(saved.backgroundSyncEnabled)
        assertTrue(saved.unifiedPushEnabled)
        assertEquals("https://ntfy.sh/mcp-test-topic", saved.unifiedPushEndpointUrl)
    }

    @Test
    fun `room migration 2 to 3 adds conversation action columns and call_history table`() {
        val dbFile = File(app.cacheDir, "migration_2_3_test.db").apply { delete() }
        val sqlite = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        sqlite.execSQL(
            """
            CREATE TABLE cached_conversations (
                id TEXT NOT NULL PRIMARY KEY,
                type TEXT NOT NULL,
                peerUserId TEXT NOT NULL,
                peerDisplayName TEXT NOT NULL,
                peerAvatarMediaId TEXT,
                peerAbout TEXT NOT NULL,
                peerIsOnline INTEGER NOT NULL,
                peerLastSeenMs INTEGER,
                isBlockedByMe INTEGER NOT NULL,
                hasBlockedMe INTEGER NOT NULL,
                lastMessageId TEXT,
                lastMessagePreview TEXT,
                lastMessageType TEXT,
                lastMessageSenderId TEXT,
                lastMessageAtMs INTEGER,
                unreadCount INTEGER NOT NULL,
                updatedAtMs INTEGER NOT NULL,
                isPrivate INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        sqlite.close()

        val callback = object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(3) {
            override fun onCreate(db: SupportSQLiteDatabase) = Unit
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(app)
                .name(dbFile.absolutePath)
                .callback(callback)
                .build()
        )
        val supportDb = helper.writableDatabase
        MyChattingProDatabase.MIGRATION_2_3.migrate(supportDb)

        supportDb.query("PRAGMA table_info(cached_conversations)").use { cursor ->
            val columns = mutableSetOf<String>()
            val nameIdx = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                columns += cursor.getString(nameIdx)
            }
            assertTrue("isPinned" in columns)
            assertTrue("isArchived" in columns)
            assertTrue("mutedUntilMs" in columns)
            assertTrue("customSoundUri" in columns)
            assertTrue("markedUnread" in columns)
            assertTrue("clearedAtMs" in columns)
        }

        supportDb.query("SELECT name FROM sqlite_master WHERE type='table' AND name='cached_call_history'").use { c ->
            assertTrue(c.moveToFirst())
        }
        helper.close()
        dbFile.delete()
    }

    @Test
    fun `room migration 3 to 4 adds replyToMessageId column to cached_messages`() {
        val dbFile = File(app.cacheDir, "migration_3_4_test.db").apply { delete() }
        val sqlite = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        sqlite.execSQL(
            """
            CREATE TABLE cached_messages (
                id TEXT NOT NULL PRIMARY KEY,
                conversationId TEXT NOT NULL,
                senderId TEXT NOT NULL,
                type TEXT NOT NULL,
                textContent TEXT,
                mediaId TEXT,
                mediaMime TEXT,
                mediaSize INTEGER,
                mediaDurationMs INTEGER,
                clientMessageId TEXT,
                createdAtMs INTEGER NOT NULL,
                deliveredAtMs INTEGER,
                readAtMs INTEGER,
                deletedForEveryoneAtMs INTEGER,
                localStatus TEXT NOT NULL,
                localUri TEXT,
                isPrivate INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        sqlite.close()

        val callback = object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(4) {
            override fun onCreate(db: SupportSQLiteDatabase) = Unit
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(app)
                .name(dbFile.absolutePath)
                .callback(callback)
                .build()
        )
        val supportDb = helper.writableDatabase
        MyChattingProDatabase.MIGRATION_3_4.migrate(supportDb)

        supportDb.query("PRAGMA table_info(cached_messages)").use { cursor ->
            val columns = mutableSetOf<String>()
            val nameIdx = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                columns += cursor.getString(nameIdx)
            }
            assertTrue("replyToMessageId" in columns)
        }

        helper.close()
        dbFile.delete()
    }
}
