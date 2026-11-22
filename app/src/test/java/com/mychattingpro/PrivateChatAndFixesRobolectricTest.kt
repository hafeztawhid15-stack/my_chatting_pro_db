package com.mychattingpro

import android.content.Context
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.R
import com.mychattingpro.core.common.AppError
import com.mychattingpro.core.common.Country
import com.mychattingpro.core.database.ConversationEntity
import com.mychattingpro.core.database.MessageEntity
import com.mychattingpro.core.database.MyChattingProDatabase
import com.mychattingpro.core.datastore.AppPreferencesDataStore
import com.mychattingpro.core.localization.AppLanguage
import com.mychattingpro.core.localization.LocaleManager
import com.mychattingpro.core.network.NetworkModule
import com.mychattingpro.core.security.EncryptedTokenStore
import com.mychattingpro.core.security.PrivateChatLockManager
import com.mychattingpro.core.security.StoredSession
import com.mychattingpro.core.theme.MyChattingProTheme
import com.mychattingpro.data.repository.AppException
import com.mychattingpro.domain.models.ActiveDeviceSession
import com.mychattingpro.domain.models.BlockedUser
import com.mychattingpro.domain.models.Conversation
import com.mychattingpro.domain.models.ConversationPeerProfile
import com.mychattingpro.domain.models.MediaAttachment
import com.mychattingpro.domain.models.Message
import com.mychattingpro.domain.models.MessageType
import com.mychattingpro.domain.models.OwnerActivitySnapshot
import com.mychattingpro.domain.models.PrivatePinStatus
import com.mychattingpro.domain.models.PublicUser
import com.mychattingpro.domain.models.UserProfile
import com.mychattingpro.domain.repository.AuthRepository
import com.mychattingpro.domain.repository.ChatRepository
import com.mychattingpro.domain.repository.OwnerRepository
import com.mychattingpro.domain.repository.PrivateChatRepository
import com.mychattingpro.domain.repository.ProfileRepository
import com.mychattingpro.domain.repository.SafetyRepository
import com.mychattingpro.features.ads.InterstitialAdManager
import com.mychattingpro.features.ads.findActivity
import com.mychattingpro.features.chats.ChatsScreen
import com.mychattingpro.features.chats.ChatsViewModel
import com.mychattingpro.features.privatechat.PinGateMode
import com.mychattingpro.features.privatechat.PrivateChatViewModel
import com.mychattingpro.features.privatechat.PrivatePinScreen
import com.mychattingpro.features.profile.ProfileScreen
import com.mychattingpro.features.profile.ProfileViewModel
import com.mychattingpro.features.settings.SettingsScreen
import com.mychattingpro.features.settings.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PrivateChatAndFixesRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

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
    fun `locale manager wraps activity preserving findActivity while localizing strings`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val wrapped = LocaleManager.localizedContext(activity, AppLanguage.BENGALI)

        assertNotNull(wrapped.findActivity())
        assertEquals(activity, wrapped.findActivity())
        assertTrue(wrapped.getString(R.string.nav_settings).isNotBlank())
        assertEquals("Details", activity.getString(R.string.menu_website))
        assertEquals("Private Chat", activity.getString(R.string.menu_private_chat))
    }

    @Test
    fun `private chat lock manager expires on inactivity timeout and manual lock`() {
        var virtualTimeMs = 1_000_000L
        val lockManager = PrivateChatLockManager(
            inactivityTimeoutMs = 30_000L,
            clock = { virtualTimeMs }
        )

        assertFalse(lockManager.isUnlocked.value)
        assertNull(lockManager.currentTokenOrNull())

        lockManager.unlock("secret-unlock-jwt", expiresInSeconds = 300)
        assertTrue(lockManager.isUnlocked.value)
        assertEquals("secret-unlock-jwt", lockManager.currentTokenOrNull())

        // Touch resets inactivity timer
        virtualTimeMs += 20_000L
        lockManager.touchActivity()
        assertEquals("secret-unlock-jwt", lockManager.currentTokenOrNull())

        // Advancing 30s+ idle locks automatically
        virtualTimeMs += 31_000L
        assertNull(lockManager.currentTokenOrNull())
        assertFalse(lockManager.isUnlocked.value)

        // Manual lock immediately clears unlock state
        lockManager.unlock("secret-unlock-jwt-2", expiresInSeconds = 300)
        assertTrue(lockManager.isUnlocked.value)
        lockManager.lock()
        assertFalse(lockManager.isUnlocked.value)
        assertNull(lockManager.currentTokenOrNull())
    }

    @Test
    fun `room db isolates private chats from regular chats`() = runTest {
        val convDao = db.conversationDao()
        val msgDao = db.messageDao()

        convDao.upsertAll(
            listOf(
                sampleConversationEntity("conv-reg", "Regular Peer", isPrivate = false),
                sampleConversationEntity("conv-priv", "Private Peer", isPrivate = true)
            )
        )
        msgDao.upsertAll(
            listOf(
                sampleMessageEntity("m-reg", "conv-reg", "Public chat text", isPrivate = false),
                sampleMessageEntity("m-priv", "conv-priv", "Secret private text", isPrivate = true)
            )
        )

        val regularConvs = convDao.observeConversations().first()
        val privateConvs = convDao.observePrivateConversations().first()
        assertEquals(listOf("conv-reg"), regularConvs.map { it.id })
        assertEquals(listOf("conv-priv"), privateConvs.map { it.id })

        val regularMsgs = msgDao.observeMessages("conv-reg").first()
        val regularQueryOnPrivConv = msgDao.observeMessages("conv-priv").first()
        val privateMsgs = msgDao.observePrivateMessages("conv-priv").first()
        assertEquals(1, regularMsgs.size)
        assertEquals(0, regularQueryOnPrivConv.size)
        assertEquals(listOf("Secret private text"), privateMsgs.map { it.bodyText })

        // Clearing private data does not remove regular conversations or messages
        convDao.clearPrivate()
        msgDao.clearPrivate()
        assertEquals(0, convDao.observePrivateConversations().first().size)
        assertEquals(1, convDao.observeConversations().first().size)
        assertEquals(1, msgDao.observeMessages("conv-reg").first().size)
    }

    @Test
    fun `profile viewmodel handles initial load failure cleanly and recovers on retry`() = runTest {
        val tokenStore = InMemoryTokenStore(
            StoredSession(
                userId = "u-1",
                phoneE164 = "+8801712345678",
                displayName = "Cached User",
                about = "Hello",
                avatarMediaId = null,
                accessToken = "acc",
                refreshToken = "ref"
            )
        )
        var shouldFailFetch = true
        val fakeProfileRepo = object : ProfileRepository {
            override suspend fun fetchMyProfile(): Result<UserProfile> {
                return if (shouldFailFetch) {
                    Result.failure(AppException(AppError.NetworkUnavailable))
                } else {
                    Result.success(
                        UserProfile(
                            id = "u-1",
                            phoneE164 = "+8801712345678",
                            displayName = "Verified User",
                            about = "Active",
                            avatarMediaId = null
                        )
                    )
                }
            }

            override suspend fun updateProfile(
                displayName: String,
                about: String,
                avatarMediaId: String?
            ): Result<UserProfile> = Result.success(
                UserProfile("u-1", "+8801712345678", displayName, about, avatarMediaId)
            )

            override suspend fun uploadProfilePhoto(uri: Uri): Result<MediaAttachment> =
                Result.failure(UnsupportedOperationException())
        }

        val vm = ProfileViewModel(fakeProfileRepo, tokenStore)
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isInitialLoading)
        assertEquals("Cached User", vm.uiState.value.displayName)
        assertTrue(vm.uiState.value.error is AppError.NetworkUnavailable)

        shouldFailFetch = false
        vm.loadProfile()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.loadFailed)
        assertEquals("Verified User", vm.uiState.value.displayName)
        assertNull(vm.uiState.value.error)

        vm.updateDisplayName("Updated Name")
        vm.saveProfile()
        advanceUntilIdle()
        assertEquals("Updated Name", vm.uiState.value.displayName)
        assertTrue(vm.uiState.value.savedSuccess)
    }

    @Test
    fun `private chat viewmodel handles first time setup, wrong pin lockout, and account auth reset`() = runTest {
        val prefs = AppPreferencesDataStore(context)
        val tokenStore = InMemoryTokenStore()
        val networkModule = NetworkModule(context, tokenStore, prefs)
        val fakePrivateRepo = FakePrivateChatRepository()

        val vm = PrivateChatViewModel(
            privateChatRepository = fakePrivateRepo,
            tokenStore = tokenStore,
            prefsStore = prefs,
            networkMonitor = networkModule.networkMonitor,
            socketManager = networkModule.realtimeSocketManager
        )

        // First-time entry: no Private PIN yet -> SETUP mode
        vm.preparePinEntry()
        advanceUntilIdle()
        assertEquals(PinGateMode.SETUP, vm.pinState.value.mode)

        // Mismatch rejection
        vm.updatePrimaryPin("11111")
        vm.updateConfirmPin("22222")
        vm.submitSetup()
        assertTrue(vm.pinState.value.error is AppError.PinMismatch)
        assertFalse(vm.pinState.value.unlockedSuccessfully)

        // Matching 5-digit PIN sets Private PIN and unlocks
        vm.updateConfirmPin("11111")
        vm.submitSetup()
        advanceUntilIdle()
        assertTrue(vm.pinState.value.unlockedSuccessfully)
        assertTrue(fakePrivateRepo.isUnlocked.value)
        vm.consumeUnlockedNavigation()

        // Subsequent entry requires entering Private PIN (UNLOCK mode)
        vm.preparePinEntry()
        advanceUntilIdle()
        assertFalse(fakePrivateRepo.isUnlocked.value)
        assertEquals(PinGateMode.UNLOCK, vm.pinState.value.mode)

        // Wrong PIN fails and triggers lockout after repeated failures
        vm.updatePrimaryPin("99999")
        vm.submitUnlock()
        advanceUntilIdle()
        assertTrue(vm.pinState.value.error is AppError.WrongPrivatePin)
        assertFalse(fakePrivateRepo.isUnlocked.value)

        // Reset Private PIN via Account Login PIN
        vm.switchToResetMode()
        assertEquals(PinGateMode.RESET, vm.pinState.value.mode)
        vm.updateLoginPin("00000") // wrong login PIN
        vm.updatePrimaryPin("55555")
        vm.updateConfirmPin("55555")
        vm.submitReset()
        advanceUntilIdle()
        assertTrue(vm.pinState.value.error is AppError.WrongPin)
        assertFalse(fakePrivateRepo.isUnlocked.value)

        // Correct Login PIN succeeds and unlocks with new Private PIN
        vm.updateLoginPin("24680")
        vm.submitReset()
        advanceUntilIdle()
        assertTrue(vm.pinState.value.unlockedSuccessfully)
        assertTrue(fakePrivateRepo.isUnlocked.value)
    }

    @Test
    fun `settings viewmodel supports changing private pin with old pin and resetting via login pin`() = runTest {
        val prefs = AppPreferencesDataStore(context)
        val tokenStore = InMemoryTokenStore(
            StoredSession("u-1", "+8801712345678", "Tawhid", "Ready", null, "a", "r")
        )
        val fakePrivateRepo = FakePrivateChatRepository()
        val settingsVm = SettingsViewModel(
            appContext = context,
            prefsStore = prefs,
            tokenStore = tokenStore,
            authRepository = FakeAuthRepo(),
            safetyRepository = FakeSafetyRepo(),
            ownerRepository = FakeOwnerRepo(),
            privateChatRepository = fakePrivateRepo
        )
        settingsVm.loadPrivatePinStatus()
        advanceUntilIdle()
        assertEquals(false, settingsVm.uiState.value.hasPrivatePin)

        // Setup from Settings
        settingsVm.setupPrivatePinFromSettings("11223", "11223")
        advanceUntilIdle()
        assertEquals(true, settingsVm.uiState.value.hasPrivatePin)
        assertEquals(R.string.private_pin_setup_success, settingsVm.uiState.value.privatePinNoticeResId)

        // Change Private PIN fails with wrong old PIN
        settingsVm.changePrivatePin("99999", "33445", "33445")
        advanceUntilIdle()
        assertTrue(settingsVm.uiState.value.privatePinError is AppError.WrongPrivatePin)

        // Change Private PIN succeeds with correct old PIN and matching confirmation
        settingsVm.changePrivatePin("11223", "33445", "33445")
        advanceUntilIdle()
        assertEquals(R.string.private_pin_change_success, settingsVm.uiState.value.privatePinNoticeResId)

        // Reset Private PIN from Settings verifies account Login PIN
        settingsVm.resetPrivatePinWithLoginPin("00000", "55667", "55667")
        advanceUntilIdle()
        assertTrue(settingsVm.uiState.value.privatePinError is AppError.WrongPin)

        settingsVm.resetPrivatePinWithLoginPin("24680", "55667", "55667")
        advanceUntilIdle()
        assertEquals(R.string.private_pin_reset_success, settingsVm.uiState.value.privatePinNoticeResId)
    }

    @Test
    fun `chats 3-dot menu shows Clear Cache, Details, and Private Chat cleanly`() {
        val prefs = AppPreferencesDataStore(context)
        val tokenStore = InMemoryTokenStore(
            StoredSession("u-1", "+8801712345678", "Tawhid", "Ready", null, "a", "r")
        )
        val networkModule = NetworkModule(context, tokenStore, prefs)
        val chatsVm = ChatsViewModel(
            chatRepository = FakeChatRepo(),
            tokenStore = tokenStore,
            prefsStore = prefs,
            networkMonitor = networkModule.networkMonitor,
            socketManager = networkModule.realtimeSocketManager
        )
        var openedPrivatePin = false

        composeRule.setContent {
            MyChattingProTheme {
                ChatsScreen(
                    viewModel = chatsVm,
                    networkModule = networkModule,
                    onOpenProfile = {},
                    onOpenConversation = {},
                    onOpenPrivateChat = { openedPrivatePin = true }
                )
            }
        }
        composeRule.onNodeWithTag("chats_three_dot_menu").performClick()
        composeRule.onNodeWithTag("menu_clear_cache").assertIsDisplayed()
        composeRule.onNodeWithTag("menu_website").assertIsDisplayed()
        composeRule.onNodeWithText("Details").assertIsDisplayed()
        composeRule.onNodeWithTag("menu_private_chat").assertIsDisplayed()
        composeRule.onNodeWithTag("menu_private_chat").performClick()
        assertTrue(openedPrivatePin)
    }

    @Test
    fun `settings screen renders cleanly without crash`() {
        val prefs = AppPreferencesDataStore(context)
        val tokenStore = InMemoryTokenStore(
            StoredSession("u-1", "+8801712345678", "Tawhid", "Ready", null, "a", "r")
        )
        val networkModule = NetworkModule(context, tokenStore, prefs)
        val fakePrivateRepo = FakePrivateChatRepository()
        val settingsVm = SettingsViewModel(
            appContext = context,
            prefsStore = prefs,
            tokenStore = tokenStore,
            authRepository = FakeAuthRepo(),
            safetyRepository = FakeSafetyRepo(),
            ownerRepository = FakeOwnerRepo(),
            privateChatRepository = fakePrivateRepo
        )

        composeRule.setContent {
            MyChattingProTheme {
                SettingsScreen(
                    viewModel = settingsVm,
                    networkModule = networkModule,
                    interstitialAdManager = InterstitialAdManager(context),
                    onOpenProfile = {},
                    onOpenNotificationSettings = {},
                    onOpenBlockedUsers = {},
                    onOpenActiveDevices = {},
                    onOpenChangeLoginPin = {},
                    onOpenRecoveryCode = {},
                    onOpenForgotLoginPin = {},
                    onLoggedOutOrDeleted = {}
                )
            }
        }
        testDispatcher.scheduler.advanceUntilIdle()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_profile_card").assertIsDisplayed()
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("settings_private_pin"))
        composeRule.onNodeWithTag("settings_private_pin").assertIsDisplayed()
    }

    @Test
    fun `profile screen renders cleanly with error retry without crash`() {
        val prefs = AppPreferencesDataStore(context)
        val tokenStore = InMemoryTokenStore(
            StoredSession("u-1", "+8801712345678", "Tawhid", "Ready", null, "a", "r")
        )
        val networkModule = NetworkModule(context, tokenStore, prefs)
        val profileVm = ProfileViewModel(
            profileRepository = object : ProfileRepository {
                override suspend fun fetchMyProfile() =
                    Result.failure<UserProfile>(AppException(AppError.NetworkUnavailable))
                override suspend fun updateProfile(displayName: String, about: String, avatarMediaId: String?) =
                    Result.success(UserProfile("u-1", "+8801712345678", displayName, about, avatarMediaId))
                override suspend fun uploadProfilePhoto(uri: Uri) =
                    Result.failure<MediaAttachment>(UnsupportedOperationException())
            },
            tokenStore = tokenStore
        )

        composeRule.setContent {
            MyChattingProTheme {
                ProfileScreen(
                    viewModel = profileVm,
                    networkModule = networkModule,
                    onBack = {}
                )
            }
        }
        testDispatcher.scheduler.advanceUntilIdle()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("profile_retry_button").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("profile_edit_toggle_button").assertIsDisplayed().performClick()
        testDispatcher.scheduler.advanceUntilIdle()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("profile_save_button").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `private pin screen renders cleanly without crash`() {
        val prefs = AppPreferencesDataStore(context)
        val tokenStore = InMemoryTokenStore(
            StoredSession("u-1", "+8801712345678", "Tawhid", "Ready", null, "a", "r")
        )
        val networkModule = NetworkModule(context, tokenStore, prefs)
        val privateVm = PrivateChatViewModel(
            privateChatRepository = FakePrivateChatRepository(),
            tokenStore = tokenStore,
            prefsStore = prefs,
            networkMonitor = networkModule.networkMonitor,
            socketManager = networkModule.realtimeSocketManager
        )

        composeRule.setContent {
            MyChattingProTheme {
                PrivatePinScreen(
                    viewModel = privateVm,
                    onBack = {},
                    onUnlocked = {}
                )
            }
        }
        composeRule.onNodeWithTag("private_pin_back_button").assertIsDisplayed()
    }

    private fun sampleConversationEntity(id: String, name: String, isPrivate: Boolean) = ConversationEntity(
        id = id,
        type = "direct",
        peerUserId = "peer-$id",
        peerDisplayName = name,
        peerAvatarMediaId = null,
        peerAbout = "Available",
        peerIsOnline = true,
        peerLastSeenMs = 1000L,
        isBlockedByMe = false,
        hasBlockedMe = false,
        lastMessageId = null,
        lastMessagePreview = "Hi",
        lastMessageType = "text",
        lastMessageSenderId = "u-1",
        lastMessageAtMs = 2000L,
        unreadCount = 0,
        updatedAtMs = 2000L,
        isPrivate = isPrivate
    )

    private fun sampleMessageEntity(id: String, convId: String, body: String, isPrivate: Boolean) = MessageEntity(
        id = id,
        conversationId = convId,
        senderId = "u-1",
        clientMessageId = "c-$id",
        messageType = "text",
        bodyText = body,
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
        readAtMs = null,
        isPrivate = isPrivate
    )

    private class InMemoryTokenStore(
        initial: StoredSession? = null
    ) : EncryptedTokenStore(ApplicationProvider.getApplicationContext()) {
        private val flow = MutableStateFlow(initial)
        override val sessionFlow: Flow<StoredSession?> = flow

        override suspend fun saveSession(session: StoredSession) {
            flow.value = session
        }

        override suspend fun getSession(): StoredSession? = flow.value

        override suspend fun updateUserProfile(displayName: String, about: String, avatarMediaId: String?) {
            flow.value = flow.value?.copy(
                displayName = displayName,
                about = about,
                avatarMediaId = avatarMediaId
            )
        }

        override suspend fun clearSession() {
            flow.value = null
        }
    }

    private class FakePrivateChatRepository : PrivateChatRepository {
        private var currentPrivatePin: String? = null
        private val validAccountLoginPin = "24680"
        private val _isUnlocked = MutableStateFlow(false)
        override val isUnlocked: StateFlow<Boolean> = _isUnlocked.asStateFlow()

        override fun lockPrivateChat() {
            _isUnlocked.value = false
        }

        override fun touchActivity() = Unit

        override suspend fun getPinStatus(): Result<PrivatePinStatus> =
            Result.success(PrivatePinStatus(hasPrivatePin = currentPrivatePin != null))

        override suspend fun setupPrivatePin(pin: String, confirmPin: String): Result<Unit> {
            if (currentPrivatePin != null) return Result.failure(AppException(AppError.PrivatePinAlreadySet))
            if (pin != confirmPin) return Result.failure(AppException(AppError.PinMismatch))
            currentPrivatePin = pin
            _isUnlocked.value = true
            return Result.success(Unit)
        }

        override suspend fun unlockPrivateChat(pin: String): Result<Unit> {
            if (currentPrivatePin == null) return Result.failure(AppException(AppError.PrivatePinNotSet))
            if (pin != currentPrivatePin) return Result.failure(AppException(AppError.WrongPrivatePin))
            _isUnlocked.value = true
            return Result.success(Unit)
        }

        override suspend fun changePrivatePin(oldPin: String, newPin: String, confirmNewPin: String): Result<Unit> {
            if (oldPin != currentPrivatePin) return Result.failure(AppException(AppError.WrongPrivatePin))
            if (newPin != confirmNewPin) return Result.failure(AppException(AppError.PinMismatch))
            currentPrivatePin = newPin
            return Result.success(Unit)
        }

        override suspend fun resetPrivatePinWithAccountAuth(
            loginPin: String,
            newPrivatePin: String,
            confirmNewPrivatePin: String
        ): Result<Unit> {
            if (loginPin != validAccountLoginPin) return Result.failure(AppException(AppError.WrongPin))
            if (newPrivatePin != confirmNewPrivatePin) return Result.failure(AppException(AppError.PinMismatch))
            currentPrivatePin = newPrivatePin
            _isUnlocked.value = true
            return Result.success(Unit)
        }

        override fun observePrivateConversations(): Flow<List<Conversation>> = flowOf(emptyList())
        override fun observePrivateConversation(conversationId: String): Flow<Conversation?> = flowOf(null)
        override suspend fun refreshPrivateConversations(): Result<Unit> = Result.success(Unit)
        override suspend fun searchUserForPrivateChat(phoneE164: String, nameFilter: String?): Result<PublicUser?> =
            Result.success(null)
        override suspend fun startPrivateDirectChat(targetUserId: String): Result<Conversation> =
            Result.failure(UnsupportedOperationException())
        override suspend fun setPrivatePinned(conversationId: String, pinned: Boolean): Result<Unit> = Result.success(Unit)
        override suspend fun setPrivateArchived(conversationId: String, archived: Boolean): Result<Unit> = Result.success(Unit)
        override suspend fun setPrivateMutedUntil(conversationId: String, mutedUntilMs: Long?): Result<Unit> = Result.success(Unit)
        override suspend fun setPrivateCustomSound(conversationId: String, soundUri: String?, soundLabel: String?): Result<Unit> = Result.success(Unit)
        override suspend fun setPrivateMarkedUnread(conversationId: String, unread: Boolean): Result<Unit> = Result.success(Unit)
        override suspend fun clearPrivateConversation(conversationId: String): Result<Unit> = Result.success(Unit)
        override suspend fun deletePrivateConversation(conversationId: String): Result<Unit> = Result.success(Unit)
        override suspend fun getPrivatePeerProfile(conversationId: String): Result<ConversationPeerProfile> =
            Result.failure(UnsupportedOperationException())
        override fun observePrivateMessages(conversationId: String): Flow<List<Message>> = flowOf(emptyList())
        override suspend fun loadLatestPrivateMessages(conversationId: String): Result<Boolean> = Result.success(false)
        override suspend fun loadOlderPrivateMessages(
            conversationId: String,
            beforeTs: Long,
            beforeId: String
        ): Result<Boolean> = Result.success(false)
        override suspend fun sendPrivateTextMessage(
            conversationId: String,
            text: String,
            clientMessageId: String?
        ): Result<Message> = Result.failure(UnsupportedOperationException())
        override suspend fun sendPrivateMediaMessage(
            conversationId: String,
            uri: Uri,
            messageType: MessageType,
            category: String,
            caption: String?,
            durationMs: Long?,
            existingClientMessageId: String?,
            onProgress: (Float) -> Unit
        ): Result<Message> = Result.failure(UnsupportedOperationException())
        override suspend fun retryFailedPrivateMessage(
            message: Message,
            onProgress: (Float) -> Unit
        ): Result<Message> = Result.failure(UnsupportedOperationException())
        override suspend fun markPrivateReceipts(
            conversationId: String,
            messageIds: List<String>,
            read: Boolean
        ): Result<Unit> = Result.success(Unit)
        override suspend fun deleteOwnPrivateMessage(messageId: String): Result<Unit> = Result.success(Unit)
    }

    private class FakeAuthRepo : AuthRepository {
        override suspend fun checkPhoneAvailable(phoneE164: String) = Result.success(true)
        override suspend fun register(
            phoneE164: String,
            country: Country,
            pin: String,
            confirmPin: String,
            displayName: String
        ): Result<UserProfile> = Result.failure(UnsupportedOperationException())
        override suspend fun login(phoneE164: String, pin: String): Result<UserProfile> =
            Result.failure(UnsupportedOperationException())
        override suspend fun logout() = Result.success(Unit)
        override suspend fun logoutAllDevices(keepCurrent: Boolean) = Result.success(Unit)
        override suspend fun changeLoginPin(currentPin: String, newPin: String, confirmNewPin: String) = Result.success(Unit)
        override suspend fun generateRecoveryCode(currentPin: String) = Result.success("ABCD-EFGH-IJKL")
        override suspend fun resetLoginPinWithRecoveryCode(
            phoneE164: String,
            recoveryCode: String,
            newPin: String,
            confirmNewPin: String
        ): Result<UserProfile> = Result.failure(UnsupportedOperationException())
        override suspend fun deleteAccount(pin: String, confirmText: String) = Result.success("deleted")
        override suspend fun getActiveSessions() = Result.success(emptyList<ActiveDeviceSession>())
        override suspend fun revokeSession(sessionId: String) = Result.success(Unit)
        override suspend fun syncNotificationEndpoint() = Result.success(Unit)
    }

    private class FakeSafetyRepo : SafetyRepository {
        override suspend fun getBlockedUsers() = Result.success(emptyList<BlockedUser>())
        override suspend fun blockUser(targetUserId: String) = Result.success(Unit)
        override suspend fun unblockUser(targetUserId: String) = Result.success(Unit)
        override suspend fun report(targetUserId: String, messageId: String?, reason: String, details: String?) =
            Result.success(Unit)
    }

    private class FakeOwnerRepo : OwnerRepository {
        override suspend fun fetchOwnerActivity(ownerKey: String, query: String, page: Int, limit: Int) =
            Result.failure<OwnerActivitySnapshot>(UnsupportedOperationException())
    }

    private class FakeChatRepo : ChatRepository {
        override fun observeConversations(): Flow<List<Conversation>> = flowOf(emptyList())
        override fun observeConversation(conversationId: String): Flow<Conversation?> = flowOf(null)
        override suspend fun refreshConversations() = Result.success(Unit)
        override suspend fun searchUser(phoneE164: String, nameFilter: String?) = Result.success<PublicUser?>(null)
        override suspend fun startDirectChat(targetUserId: String) =
            Result.failure<Conversation>(UnsupportedOperationException())
        override suspend fun setPinned(conversationId: String, pinned: Boolean) = Result.success(Unit)
        override suspend fun setArchived(conversationId: String, archived: Boolean) = Result.success(Unit)
        override suspend fun setMutedUntil(conversationId: String, mutedUntilMs: Long?) = Result.success(Unit)
        override suspend fun setCustomSound(conversationId: String, soundUri: String?, soundLabel: String?) = Result.success(Unit)
        override suspend fun setMarkedUnread(conversationId: String, unread: Boolean) = Result.success(Unit)
        override suspend fun clearConversation(conversationId: String) = Result.success(Unit)
        override suspend fun deleteConversation(conversationId: String) = Result.success(Unit)
        override suspend fun getPeerProfile(conversationId: String) =
            Result.failure<ConversationPeerProfile>(UnsupportedOperationException())
    }
}
