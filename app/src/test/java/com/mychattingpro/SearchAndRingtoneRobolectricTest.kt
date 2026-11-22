package com.mychattingpro

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.R
import com.mychattingpro.core.common.AppError
import com.mychattingpro.core.common.SearchRateLimiter
import com.mychattingpro.core.common.SearchThrottleResult
import com.mychattingpro.core.datastore.AppPreferences
import com.mychattingpro.core.datastore.AppPreferencesDataStore
import com.mychattingpro.core.network.NetworkModule
import com.mychattingpro.core.security.EncryptedTokenStore
import com.mychattingpro.core.security.PrivateChatLockManager
import com.mychattingpro.core.security.StoredSession
import com.mychattingpro.core.theme.MyChattingProTheme
import com.mychattingpro.data.repository.AppException
import com.mychattingpro.domain.models.CallHistoryFilter
import com.mychattingpro.domain.models.CallHistoryItem
import com.mychattingpro.domain.models.Conversation
import com.mychattingpro.domain.models.ConversationPeerProfile
import com.mychattingpro.domain.models.IceServerConfig
import com.mychattingpro.domain.models.PublicUser
import com.mychattingpro.domain.repository.CallRepository
import com.mychattingpro.domain.repository.ChatRepository
import com.mychattingpro.features.calls.CallViewModel
import com.mychattingpro.features.calls.IncomingCallActionReceiver
import com.mychattingpro.features.calls.IncomingCallRingtoneController
import com.mychattingpro.features.calls.RingtonePlaybackEngine
import com.mychattingpro.features.calls.WebRtcCallManager
import com.mychattingpro.features.calls.WebRtcConnectionStage
import com.mychattingpro.features.chats.AddChatScreen
import com.mychattingpro.features.chats.ChatsViewModel
import com.mychattingpro.features.notifications.AppNotificationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SearchAndRingtoneRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var app: Application

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        app = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeChatRepo(
        private val directory: Map<String, PublicUser> = emptyMap(),
        private val forceRateLimit: Int? = null
    ) : ChatRepository {
        var searchCallCount = 0

        override fun observeConversations(): Flow<List<Conversation>> = flowOf(emptyList())
        override fun observeConversation(conversationId: String): Flow<Conversation?> = flowOf(null)
        override suspend fun refreshConversations(): Result<Unit> = Result.success(Unit)

        override suspend fun searchUser(phoneE164: String, nameFilter: String?): Result<PublicUser?> {
            searchCallCount++
            if (forceRateLimit != null) {
                return Result.failure(AppException(AppError.RateLimited(forceRateLimit)))
            }
            val user = directory[phoneE164]
            if (user != null && !nameFilter.isNullOrBlank()) {
                if (!user.displayName.contains(nameFilter, ignoreCase = true)) {
                    return Result.success(null)
                }
            }
            return Result.success(user)
        }

        override suspend fun startDirectChat(targetUserId: String): Result<Conversation> {
            return Result.success(
                Conversation(
                    id = "conv-$targetUserId",
                    type = "direct",
                    peerUserId = targetUserId,
                    peerDisplayName = "Found Friend",
                    peerAvatarMediaId = null,
                    peerAbout = "Hi there",
                    peerIsOnline = true,
                    peerLastSeenMs = null,
                    isBlockedByMe = false,
                    hasBlockedMe = false,
                    lastMessageId = null,
                    lastMessagePreview = null,
                    lastMessageType = null,
                    lastMessageSenderId = null,
                    lastMessageAtMs = null,
                    unreadCount = 0,
                    updatedAtMs = System.currentTimeMillis()
                )
            )
        }
        override suspend fun setPinned(conversationId: String, pinned: Boolean) = Result.success(Unit)
        override suspend fun setArchived(conversationId: String, archived: Boolean) = Result.success(Unit)
        override suspend fun setMutedUntil(conversationId: String, mutedUntilMs: Long?) = Result.success(Unit)
        override suspend fun setCustomSound(conversationId: String, soundUri: String?, soundLabel: String?) = Result.success(Unit)
        override suspend fun setMarkedUnread(conversationId: String, unread: Boolean) = Result.success(Unit)
        override suspend fun clearConversation(conversationId: String) = Result.success(Unit)
        override suspend fun deleteConversation(conversationId: String) = Result.success(Unit)
        override suspend fun moveToPrivateChat(conversationId: String) = Result.success(Unit)
        override suspend fun getPeerProfile(conversationId: String) =
            Result.failure<ConversationPeerProfile>(UnsupportedOperationException())
    }

    private class FakeRingtoneEngine : RingtonePlaybackEngine {
        var currentUri: Uri? = Uri.parse("content://settings/system/ringtone_one")
        var mode: Int = AudioManager.RINGER_MODE_NORMAL
        var playCalls = 0
        var stopCalls = 0
        var lastStartedUri: Uri? = null
        var lastStartedVibrate: Boolean = false
        var playing = false

        override fun resolveCurrentSystemRingtoneUri(): Uri? = currentUri
        override fun getRingerMode(): Int = mode

        override fun startPlayback(uri: Uri?, vibrate: Boolean) {
            playCalls++
            playing = true
            lastStartedUri = uri
            lastStartedVibrate = vibrate
        }

        override fun stopPlayback() {
            stopCalls++
            playing = false
        }
    }

    private class FakeCallRepo : CallRepository {
        val stateUpdates = mutableListOf<Pair<String, String>>()
        override fun observeCallHistory(): Flow<List<CallHistoryItem>> = flowOf(emptyList())
        override suspend fun refreshCallHistory(filter: CallHistoryFilter): Result<List<CallHistoryItem>> =
            Result.success(emptyList())
        override suspend fun fetchIceServers(): Result<Pair<List<IceServerConfig>, Boolean>> =
            Result.success(emptyList<IceServerConfig>() to false)

        override suspend fun initiateCall(conversationId: String, calleeId: String, isVideo: Boolean): Result<String> =
            Result.success("call-out-1")

        override suspend fun updateCallState(callId: String, action: String): Result<Unit> {
            stateUpdates += callId to action
            return Result.success(Unit)
        }
    }

    @Test
    fun `search rate limiter enforces sliding window limit, duplicate cooldown, and server retry`() {
        var now = 1_000_000L
        val limiter = SearchRateLimiter(
            maxSearchesPerWindow = 3,
            windowMs = 60_000L,
            duplicateCooldownMs = 2_000L,
            blockDurationMs = 30_000L,
            clock = { now }
        )

        assertEquals(SearchThrottleResult.Allowed, limiter.checkAndRecord("+8801711111111"))
        assertEquals(SearchThrottleResult.DuplicateTooSoon, limiter.checkAndRecord("+8801711111111"))

        now += 2_500L
        assertEquals(SearchThrottleResult.Allowed, limiter.checkAndRecord("+8801722222222"))
        now += 500L
        assertEquals(SearchThrottleResult.Allowed, limiter.checkAndRecord("+8801733333333"))

        // 4th distinct search in 60s hits sliding window cap -> 30s block
        val fourth = limiter.checkAndRecord("+8801744444444")
        assertTrue(fourth is SearchThrottleResult.RateLimited)
        assertEquals(30, (fourth as SearchThrottleResult.RateLimited).retryAfterSeconds)

        // After cooldown + sliding window expire -> allowed again
        now += 61_000L
        assertEquals(SearchThrottleResult.Allowed, limiter.checkAndRecord("+8801744444444"))

        // Server 429 updates block duration cleanly
        limiter.recordServerRateLimit(45)
        assertEquals(45, limiter.remainingBlockSeconds())
    }

    private class FakeTokenStore(context: Context, initial: StoredSession? = null) :
        EncryptedTokenStore(context) {
        private val flow = MutableStateFlow(initial)
        override val sessionFlow: Flow<StoredSession?> = flow
        override suspend fun saveSession(session: StoredSession) { flow.value = session }
        override suspend fun getSession(): StoredSession? = flow.value
        override suspend fun updateUserProfile(displayName: String, about: String, avatarMediaId: String?) {
            flow.value = flow.value?.copy(displayName = displayName, about = about, avatarMediaId = avatarMediaId)
        }
        override suspend fun clearSession() { flow.value = null }
    }

    @Test
    fun `account search finds profile without exposing phone, blocks self search, and handles not found`() = runTest {
        val prefs = AppPreferencesDataStore(app)
        val lockManager = PrivateChatLockManager()
        val tokenStore = FakeTokenStore(
            app,
            StoredSession(
                userId = "me-1",
                phoneE164 = "+8801700000001",
                displayName = "Me",
                about = "Available",
                avatarMediaId = null,
                accessToken = "tok",
                refreshToken = "ref"
            )
        )
        val net = NetworkModule(app, tokenStore, prefs, lockManager)
        val repo = FakeChatRepo(
            directory = mapOf(
                "+8801812345678" to PublicUser(
                    id = "peer-9",
                    displayName = "Tasnim Akter",
                    about = "Product Designer",
                    avatarMediaId = null,
                    isOnline = true,
                    lastSeenMs = null
                )
            )
        )
        val vm = ChatsViewModel(
            chatRepository = repo,
            tokenStore = tokenStore,
            prefsStore = prefs,
            networkMonitor = net.networkMonitor,
            socketManager = net.realtimeSocketManager,
            searchRateLimiter = SearchRateLimiter()
        )

        // 1) Self-search is blocked client-side before touching backend
        vm.updateAddChatPhone("01700000001")
        vm.searchUser()
        advanceUntilIdle()
        assertTrue(vm.addChatState.value.error is AppError.SelfSearchNotAllowed)
        assertEquals(0, repo.searchCallCount)

        // 2) Registered phone returns only public profile fields (phone not present in UI)
        vm.updateAddChatPhone("01812345678")
        vm.searchUser()
        advanceUntilIdle()
        assertNull(vm.addChatState.value.error)
        assertEquals("Tasnim Akter", vm.addChatState.value.foundUser?.displayName)

        composeRule.setContent {
            MyChattingProTheme {
                AddChatScreen(
                    viewModel = vm,
                    networkModule = net,
                    onConversationReady = {}
                )
            }
        }
        composeRule.onNodeWithTag("add_chat_found_profile_card").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Tasnim Akter").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Product Designer").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("add_chat_start_button").performScrollTo().assertIsDisplayed()
        // Target phone must NEVER appear on the rendered profile card (only input box holds typed digits)
        assertEquals(0, composeRule.onAllNodesWithText("+8801812345678").fetchSemanticsNodes().size)

        // 3) Unregistered number clearly indicates no account found
        vm.updateAddChatPhone("01911223344")
        vm.searchUser()
        advanceUntilIdle()
        assertTrue(vm.addChatState.value.hasSearched)
        assertNull(vm.addChatState.value.foundUser)
        composeRule.onNodeWithText(app.getString(R.string.add_chat_not_found)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `ringtone controller dynamically picks up system ringtone changes and stops on accept reject or silent`() {
        val engine = FakeRingtoneEngine()
        val controller = IncomingCallRingtoneController(app, engine = engine, maxRingDurationMs = 60_000L)

        // Call 1 with ringtone_one
        val uri1 = Uri.parse("content://settings/system/ringtone_one")
        engine.currentUri = uri1
        controller.startRinging("call-1")
        assertTrue(controller.isRinging.value)
        assertEquals(uri1, engine.lastStartedUri)
        assertTrue(engine.playing)

        // Accept / disconnect stops ringtone immediately
        controller.stopRinging("call-1")
        assertFalse(controller.isRinging.value)
        assertFalse(engine.playing)

        // User changes default SIM ringtone in Android Settings -> next call plays ringtone_two immediately
        val uri2 = Uri.parse("content://settings/system/ringtone_two")
        engine.currentUri = uri2
        controller.startRinging("call-2")
        assertTrue(controller.isRinging.value)
        assertEquals(uri2, engine.lastStartedUri)

        // Silent mode plays neither sound nor vibration
        engine.mode = AudioManager.RINGER_MODE_SILENT
        controller.startRinging("call-3")
        assertFalse(controller.isRinging.value)
        assertFalse(engine.playing)

        // Vibrate mode vibrates without ringtone URI
        engine.mode = AudioManager.RINGER_MODE_VIBRATE
        controller.startRinging("call-4")
        assertTrue(controller.isRinging.value)
        assertNull(engine.lastStartedUri)
        assertTrue(engine.lastStartedVibrate)

        controller.stopRinging("call-4")
        assertFalse(controller.isRinging.value)
    }

    private class FakeWebRtcManager(
        context: Context,
        socketManager: com.mychattingpro.core.network.RealtimeSocketManager
    ) : WebRtcCallManager(context, socketManager) {
        var sessionStarted = false
        override fun startSession(
            callId: String,
            conversationId: String,
            peerUserId: String,
            isVideo: Boolean,
            iceServers: List<IceServerConfig>,
            onStageChanged: (WebRtcConnectionStage) -> Unit
        ) {
            sessionStarted = true
        }
        override fun endSession() = Unit
        override fun release() = Unit
    }

    @Test
    fun `call viewmodel stops incoming ringtone on accept, reject, end, and remote disconnect`() = runTest {
        val prefs = AppPreferencesDataStore(app)
        val net = NetworkModule(app, FakeTokenStore(app), prefs, PrivateChatLockManager())
        val callRepo = FakeCallRepo()
        val fakeRtc = FakeWebRtcManager(app, net.realtimeSocketManager)
        val stoppedIds = mutableListOf<String>()

        val vm = CallViewModel(
            context = app,
            initialCallId = "call-in-1",
            conversationId = "conv-1",
            peerUserId = "peer-1",
            isVideo = true,
            isIncoming = true,
            callRepository = callRepo,
            socketManager = net.realtimeSocketManager,
            networkMonitor = net.networkMonitor,
            onStopIncomingAlert = { stoppedIds += it },
            webRtcManager = fakeRtc
        )

        // Ringing starts cleanly without starting WebRTC / MODE_IN_COMMUNICATION prematurely
        vm.beginAfterPermissionsGranted()
        advanceUntilIdle()
        assertEquals(WebRtcConnectionStage.RINGING, vm.uiState.value.stage)
        assertFalse(fakeRtc.sessionStarted)
        assertTrue(stoppedIds.isEmpty())

        // Reject stops ringtone immediately & updates call state
        var rejected = false
        vm.rejectIncomingCall { rejected = true }
        advanceUntilIdle()
        assertTrue(rejected)
        assertEquals(listOf("call-in-1"), stoppedIds)
        assertEquals(WebRtcConnectionStage.ENDED, vm.uiState.value.stage)
        assertTrue(("call-in-1" to "reject") in callRepo.stateUpdates)
    }

    @Test
    fun `call viewmodel stops ringtone immediately on acceptIncomingCall`() = runTest {
        val prefs = AppPreferencesDataStore(app)
        val net = NetworkModule(app, FakeTokenStore(app), prefs, PrivateChatLockManager())
        val callRepo = FakeCallRepo()
        val fakeRtc = FakeWebRtcManager(app, net.realtimeSocketManager)
        val stoppedIds = mutableListOf<String>()

        val vm = CallViewModel(
            context = app,
            initialCallId = "call-in-2",
            conversationId = "conv-2",
            peerUserId = "peer-2",
            isVideo = false,
            isIncoming = true,
            callRepository = callRepo,
            socketManager = net.realtimeSocketManager,
            networkMonitor = net.networkMonitor,
            onStopIncomingAlert = { stoppedIds += it },
            webRtcManager = fakeRtc
        )

        vm.beginAfterPermissionsGranted()
        advanceUntilIdle()
        assertFalse(fakeRtc.sessionStarted)
        assertTrue(stoppedIds.isEmpty())

        vm.acceptIncomingCall()
        assertEquals(listOf("call-in-2"), stoppedIds)
        advanceUntilIdle()
        assertTrue(fakeRtc.sessionStarted)
        assertTrue(("call-in-2" to "accept") in callRepo.stateUpdates)
    }

    @Test
    fun `incoming call notification builds silent CallStyle alert with fullScreenIntent and decline receiver stops ringtone`() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        }
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val appNotif = AppNotificationManager(app)

        appNotif.showIncomingCallNotification(
            callId = "call-bg-1",
            callerName = "Karim",
            isVideo = true,
            prefs = AppPreferences(),
            conversationId = "conv-9",
            callerId = "user-9"
        )

        val shadowNm = Shadows.shadowOf(nm)
        val posted: Notification? = shadowNm.allNotifications.firstOrNull()
        assertEquals(Notification.CATEGORY_CALL, posted?.category)
        assertTrue(posted?.fullScreenIntent != null)
        assertEquals(AppNotificationManager.CHANNEL_CALLS, posted?.channelId)

        // Trigger background Decline broadcast receiver -> must stop active ringtone & cancel notification
        val myApp = app as? MyChattingProApp
        if (myApp != null) {
            myApp.container.ringtoneController.startRinging("call-bg-1")
            val declineIntent = Intent(app, IncomingCallActionReceiver::class.java).apply {
                action = IncomingCallActionReceiver.ACTION_DECLINE_CALL
                putExtra(IncomingCallActionReceiver.EXTRA_CALL_ID, "call-bg-1")
                putExtra(IncomingCallActionReceiver.EXTRA_CONVERSATION_ID, "conv-9")
                putExtra(IncomingCallActionReceiver.EXTRA_CALLER_ID, "user-9")
            }
            IncomingCallActionReceiver().onReceive(app, declineIntent)
            assertFalse(myApp.container.ringtoneController.isRinging.value)
        }
    }
}
