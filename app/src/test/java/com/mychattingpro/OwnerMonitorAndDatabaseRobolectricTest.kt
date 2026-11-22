package com.mychattingpro

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mychattingpro.core.common.AppError
import com.mychattingpro.core.common.Country
import com.mychattingpro.core.database.ConversationEntity
import com.mychattingpro.core.database.MessageEntity
import com.mychattingpro.core.database.MyChattingProDatabase
import com.mychattingpro.core.datastore.AppPreferencesDataStore
import com.mychattingpro.core.network.OwnerActivityResponseDto
import com.mychattingpro.core.security.EncryptedTokenStore
import com.mychattingpro.data.repository.AppException
import com.mychattingpro.domain.models.ActiveDeviceSession
import com.mychattingpro.domain.models.BlockedUser
import com.mychattingpro.domain.models.OwnerActivitySnapshot
import com.mychattingpro.domain.models.OwnerActivitySummary
import com.mychattingpro.domain.models.OwnerTrackedUser
import com.mychattingpro.domain.models.UserProfile
import com.mychattingpro.domain.repository.AuthRepository
import com.mychattingpro.domain.repository.OwnerRepository
import com.mychattingpro.domain.repository.SafetyRepository
import com.mychattingpro.features.settings.SettingsViewModel
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OwnerMonitorAndDatabaseRobolectricTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var context: Context
    private lateinit var db: MyChattingProDatabase

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, MyChattingProDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `owner activity response dto parses summary pagination and users cleanly`() {
        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        val adapter = moshi.adapter(OwnerActivityResponseDto::class.java)
        val json = """
            {
              "summary": {
                "totalRegisteredUsers": 42,
                "currentlyOnlineUsers": 5,
                "activeLast24hUsers": 18,
                "activeSessionsCount": 21,
                "generatedAtMs": 1730000000000
              },
              "pagination": {
                "page": 1,
                "limit": 20,
                "totalCount": 42,
                "hasMore": true
              },
              "users": [
                {
                  "userId": "u-1",
                  "displayName": "Tawhid",
                  "countryIso2": "BD",
                  "maskedPhone": "+880••••5678",
                  "isOnline": true,
                  "lastSeenMs": 1730000000000,
                  "createdAtMs": 1720000000000,
                  "latestDeviceName": "Pixel 9",
                  "latestPlatform": "android",
                  "latestAppVersion": "1.0.0",
                  "activeSessionCount": 1
                }
              ]
            }
        """.trimIndent()

        val parsed = adapter.fromJson(json)
        assertNotNull(parsed)
        assertEquals(42, parsed?.summary?.totalRegisteredUsers)
        assertEquals(true, parsed?.pagination?.hasMore)
        assertEquals(42, parsed?.pagination?.totalCount)
        assertEquals("Tawhid", parsed?.users?.firstOrNull()?.displayName)
    }

    @Test
    fun `settings viewmodel handles owner monitor search pagination and owner access denied`() = runTest {
        val prefsStore = AppPreferencesDataStore(context)
        val tokenStore = object : EncryptedTokenStore(context) {
            private var storedOwnerKey: String? = null
            override val sessionFlow: kotlinx.coroutines.flow.Flow<com.mychattingpro.core.security.StoredSession?> =
                kotlinx.coroutines.flow.MutableStateFlow(null)

            override suspend fun saveOwnerKey(ownerKey: String?) {
                storedOwnerKey = ownerKey?.trim()?.takeIf { it.isNotEmpty() }
            }

            override suspend fun getOwnerKey(): String? = storedOwnerKey
        }
        tokenStore.saveOwnerKey(null)

        var forceAccessDenied = false
        val fakeOwnerRepo = object : OwnerRepository {
            override suspend fun fetchOwnerActivity(
                ownerKey: String,
                query: String,
                page: Int,
                limit: Int
            ): Result<OwnerActivitySnapshot> {
                if (forceAccessDenied) {
                    return Result.failure(AppException(AppError.OwnerAccessDenied))
                }
                if (ownerKey != "secret-owner-key") {
                    return Result.failure(AppException(AppError.InvalidOwnerKey))
                }
                val users = when (page) {
                    1 -> listOf(sampleTrackedUser("u1", "Alice"), sampleTrackedUser("u2", "Bob"))
                    else -> listOf(sampleTrackedUser("u3", "Charlie"))
                }.filter { query.isBlank() || it.displayName.contains(query, ignoreCase = true) }

                return Result.success(
                    OwnerActivitySnapshot(
                        summary = OwnerActivitySummary(3, 1, 2, 3, 1000L),
                        users = users,
                        page = page,
                        totalCount = 3,
                        hasMore = page == 1 && query.isBlank()
                    )
                )
            }
        }

        val vm = SettingsViewModel(
            appContext = context,
            prefsStore = prefsStore,
            tokenStore = tokenStore,
            authRepository = FakeAuthRepository(),
            safetyRepository = FakeSafetyRepository(),
            ownerRepository = fakeOwnerRepo
        )

        vm.unlockAndLoadOwnerActivity("secret-owner-key")
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.ownerActivity?.users?.size)
        assertTrue(vm.uiState.value.ownerActivity?.hasMore == true)

        vm.loadMoreOwnerUsers()
        advanceUntilIdle()
        assertEquals(3, vm.uiState.value.ownerActivity?.users?.size)
        assertFalse(vm.uiState.value.ownerActivity?.hasMore ?: true)

        vm.updateOwnerSearchQuery("Alice")
        vm.searchOwnerUsers()
        advanceUntilIdle()
        assertEquals(listOf("u1"), vm.uiState.value.ownerActivity?.users?.map { it.userId })

        forceAccessDenied = true
        vm.refreshOwnerActivity()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.ownerError is AppError.OwnerAccessDenied)
        assertFalse(vm.uiState.value.hasSavedOwnerKey)
        assertNull(tokenStore.getOwnerKey())
    }

    @Test
    fun `room cache stores conversations and messages cleanly and respects delete`() = runTest {
        val convDao = db.conversationDao()
        val msgDao = db.messageDao()

        convDao.upsert(
            ConversationEntity(
                id = "conv-1",
                type = "direct",
                peerUserId = "u-2",
                peerDisplayName = "Rahim",
                peerAvatarMediaId = null,
                peerAbout = "Available",
                peerIsOnline = true,
                peerLastSeenMs = 1000L,
                isBlockedByMe = false,
                hasBlockedMe = false,
                lastMessageId = "m-1",
                lastMessagePreview = "Hello",
                lastMessageType = "text",
                lastMessageSenderId = "u-1",
                lastMessageAtMs = 2000L,
                unreadCount = 0,
                updatedAtMs = 2000L
            )
        )

        msgDao.upsert(
            MessageEntity(
                id = "m-1",
                conversationId = "conv-1",
                senderId = "u-1",
                clientMessageId = "c-1",
                messageType = "text",
                bodyText = "Hello Supabase + Cloudflare",
                mediaId = null,
                mediaCategory = null,
                mediaFilename = null,
                mediaMimeType = null,
                mediaSizeBytes = null,
                mediaDurationMs = null,
                localStatus = "SENT",
                localUri = null,
                uploadProgress = null,
                deletedForAllAtMs = null,
                createdAtMs = 2000L,
                deliveredAtMs = null,
                readAtMs = null
            )
        )

        assertEquals(1, convDao.observeConversations().first().size)
        assertEquals("Hello Supabase + Cloudflare", msgDao.observeMessages("conv-1").first().first().bodyText)

        msgDao.markDeletedForAll("m-1", 3000L)
        val deleted = msgDao.observeMessages("conv-1").first().first()
        assertEquals(3000L, deleted.deletedForAllAtMs)
        assertNull(deleted.bodyText)
    }

    private fun sampleTrackedUser(id: String, name: String) = OwnerTrackedUser(
        userId = id,
        displayName = name,
        countryIso2 = "BD",
        maskedPhone = "+880••••1234",
        isOnline = true,
        lastSeenMs = 1000L,
        createdAtMs = 500L,
        latestDeviceName = "Android Device",
        latestPlatform = "android",
        latestAppVersion = "1.0.0",
        activeSessionCount = 1
    )

    private class FakeAuthRepository : AuthRepository {
        override suspend fun checkPhoneAvailable(phoneE164: String): Result<Boolean> =
            Result.success(true)

        override suspend fun register(
            phoneE164: String,
            country: Country,
            pin: String,
            confirmPin: String,
            displayName: String
        ): Result<UserProfile> = Result.failure(UnsupportedOperationException())

        override suspend fun login(phoneE164: String, pin: String): Result<UserProfile> =
            Result.failure(UnsupportedOperationException())

        override suspend fun logout(): Result<Unit> = Result.success(Unit)
        override suspend fun logoutAllDevices(keepCurrent: Boolean): Result<Unit> = Result.success(Unit)
        override suspend fun changeLoginPin(currentPin: String, newPin: String, confirmNewPin: String): Result<Unit> = Result.success(Unit)
        override suspend fun generateRecoveryCode(currentPin: String): Result<String> = Result.success("ABCD-EFGH-IJKL")
        override suspend fun resetLoginPinWithRecoveryCode(
            phoneE164: String,
            recoveryCode: String,
            newPin: String,
            confirmNewPin: String
        ): Result<UserProfile> = Result.failure(UnsupportedOperationException())
        override suspend fun deleteAccount(pin: String, confirmText: String): Result<String> = Result.success("deleted")
        override suspend fun getActiveSessions(): Result<List<ActiveDeviceSession>> = Result.success(emptyList())
        override suspend fun revokeSession(sessionId: String): Result<Unit> = Result.success(Unit)
        override suspend fun syncNotificationEndpoint(): Result<Unit> = Result.success(Unit)
    }

    private class FakeSafetyRepository : SafetyRepository {
        override suspend fun getBlockedUsers(): Result<List<BlockedUser>> = Result.success(emptyList())
        override suspend fun blockUser(targetUserId: String): Result<Unit> = Result.success(Unit)
        override suspend fun unblockUser(targetUserId: String): Result<Unit> = Result.success(Unit)
        override suspend fun report(
            targetUserId: String,
            messageId: String?,
            reason: String,
            details: String?
        ): Result<Unit> = Result.success(Unit)
    }
}
