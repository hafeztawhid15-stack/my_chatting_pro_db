package com.mychattingpro

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.R
import com.mychattingpro.core.datastore.AppPreferences
import com.mychattingpro.core.localization.ProvideAppLocalization
import com.mychattingpro.core.navigation.AppRoute
import com.mychattingpro.core.theme.MyChattingProTheme
import com.mychattingpro.features.ads.findActivity
import com.mychattingpro.features.auth.AuthViewModel
import com.mychattingpro.features.auth.CreateAccountScreen
import com.mychattingpro.features.auth.LoginScreen
import com.mychattingpro.features.auth.SplashScreen
import com.mychattingpro.features.auth.WelcomeScreen
import com.mychattingpro.features.calls.CallHistoryScreen
import com.mychattingpro.features.calls.CallHistoryViewModel
import com.mychattingpro.features.calls.CallScreen
import com.mychattingpro.features.calls.CallViewModel
import com.mychattingpro.features.chats.ChatsViewModel
import com.mychattingpro.features.chats.MainTabsScreen
import com.mychattingpro.features.messages.ChatDetailScreen
import com.mychattingpro.features.messages.ChatDetailViewModel
import com.mychattingpro.features.messages.MediaViewerScreen
import com.mychattingpro.features.notifications.AppNotificationManager
import com.mychattingpro.features.notifications.NotificationsScreen
import com.mychattingpro.features.notifications.RealtimeSyncService
import com.mychattingpro.features.privatechat.PrivateChatViewModel
import com.mychattingpro.features.privatechat.PrivateChatsScreen
import com.mychattingpro.features.privatechat.PrivatePinScreen
import com.mychattingpro.features.profile.ProfileScreen
import com.mychattingpro.features.profile.ProfileViewModel
import com.mychattingpro.features.settings.ActiveDevicesScreen
import com.mychattingpro.features.settings.BlockedUsersScreen
import com.mychattingpro.features.settings.ChangeLoginPinScreen
import com.mychattingpro.features.settings.ForgotLoginPinScreen
import com.mychattingpro.features.settings.NotificationSettingsScreen
import com.mychattingpro.features.settings.OwnerActivityScreen
import com.mychattingpro.features.settings.RecoveryCodeScreen
import com.mychattingpro.features.settings.SettingsViewModel
import java.util.UUID

private data class PendingCallLaunch(
    val callId: String,
    val conversationId: String,
    val peerUserId: String,
    val peerName: String,
    val peerAvatarMediaId: String?,
    val isVideo: Boolean,
    val autoAccept: Boolean
)

class MainActivity : ComponentActivity() {
    private var pendingCallLaunch by mutableStateOf<PendingCallLaunch?>(null)
    private var pendingNavigationTarget by mutableStateOf<String?>(null)
    private var cachedHasSession = false
    private var cachedBackgroundSyncEnabled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as MyChattingProApp
        val container = app.container
        handleLaunchIntent(intent, container)

        setContent {
            val prefs by container.prefsStore.preferencesFlow.collectAsStateWithLifecycle(
                initialValue = AppPreferences()
            )
            val session by container.tokenStore.sessionFlow.collectAsStateWithLifecycle(
                initialValue = null
            )

            LaunchedEffect(prefs.backgroundSyncEnabled, session?.userId) {
                cachedHasSession = session != null
                cachedBackgroundSyncEnabled = prefs.backgroundSyncEnabled
                if (session != null) {
                    container.networkModule.realtimeSocketManager.start()
                } else {
                    container.networkModule.realtimeSocketManager.stop()
                }
                if (prefs.backgroundSyncEnabled && session != null) {
                    RealtimeSyncService.start(applicationContext)
                } else if (!prefs.backgroundSyncEnabled) {
                    RealtimeSyncService.stop(applicationContext)
                }
            }

            ProvideAppLocalization(language = prefs.language) {
                MyChattingProTheme(themePreference = prefs.themePreference) {
                    MyChattingProNavRoot(
                        container = container,
                        pendingCallLaunch = pendingCallLaunch,
                        onCallLaunchConsumed = { pendingCallLaunch = null },
                        pendingNavigationTarget = pendingNavigationTarget,
                        onNavigationTargetConsumed = { pendingNavigationTarget = null }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val container = (application as? MyChattingProApp)?.container ?: return
        handleLaunchIntent(intent, container)
    }

    private fun handleLaunchIntent(intent: Intent?, container: AppContainer) {
        if (intent == null) return
        val navTarget = intent.getStringExtra(AppNotificationManager.EXTRA_NAVIGATE_TO)
        if (!navTarget.isNullOrBlank()) {
            pendingNavigationTarget = navTarget
            intent.removeExtra(AppNotificationManager.EXTRA_NAVIGATE_TO)
        }
        val callId = intent.getStringExtra(AppNotificationManager.EXTRA_CALL_ID).orEmpty()
        if (callId.isBlank()) return

        val actionMode = intent.getStringExtra(AppNotificationManager.EXTRA_CALL_ACTION)
        val autoAccept = actionMode == AppNotificationManager.CALL_ACTION_ANSWER
        if (autoAccept) {
            container.stopIncomingCallAlert(callId)
        }

        val currentPrompt = container.incomingCall.value
        val conversationId = intent.getStringExtra(AppNotificationManager.EXTRA_CONVERSATION_ID)
            ?.takeIf { it.isNotBlank() }
            ?: currentPrompt?.conversationId.orEmpty()
        val callerId = intent.getStringExtra(AppNotificationManager.EXTRA_CALLER_ID)
            ?.takeIf { it.isNotBlank() }
            ?: currentPrompt?.callerId.orEmpty()
        val callerName = intent.getStringExtra(AppNotificationManager.EXTRA_CALLER_NAME)
            ?.takeIf { it.isNotBlank() }
            ?: currentPrompt?.callerName
            ?: getString(R.string.app_name)
        val callerAvatar = intent.getStringExtra(AppNotificationManager.EXTRA_CALLER_AVATAR)
            ?: currentPrompt?.callerAvatarMediaId
        val isVideo = intent.getBooleanExtra(
            AppNotificationManager.EXTRA_IS_VIDEO,
            currentPrompt?.isVideo ?: false
        )

        container.consumeIncomingCallPrompt()
        pendingCallLaunch = PendingCallLaunch(
            callId = callId,
            conversationId = conversationId,
            peerUserId = callerId,
            peerName = callerName,
            peerAvatarMediaId = callerAvatar,
            isVideo = isVideo,
            autoAccept = autoAccept
        )
    }

    override fun onStart() {
        super.onStart()
        val container = (application as? MyChattingProApp)?.container ?: return
        container.interstitialAdManager.preload(resetRetry = true)
        if (cachedHasSession) {
            container.networkModule.realtimeSocketManager.sendPresencePing()
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            val container = (application as? MyChattingProApp)?.container ?: return
            container.privateChatLockManager.lock()
            if (cachedHasSession && !cachedBackgroundSyncEnabled) {
                container.networkModule.realtimeSocketManager.pauseForBackground()
            }
        }
    }
}

@Composable
private fun MyChattingProNavRoot(
    container: AppContainer,
    pendingCallLaunch: PendingCallLaunch?,
    onCallLaunchConsumed: () -> Unit,
    pendingNavigationTarget: String?,
    onNavigationTargetConsumed: () -> Unit
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val incomingCall by container.incomingCall.collectAsStateWithLifecycle()
    val session by container.tokenStore.sessionFlow.collectAsStateWithLifecycle(initialValue = null)
    var activeCallScreenId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(pendingNavigationTarget, session?.userId) {
        val target = pendingNavigationTarget ?: return@LaunchedEffect
        if (session == null) return@LaunchedEffect
        onNavigationTargetConsumed()
        when (target) {
            AppNotificationManager.NAV_ACTIVE_DEVICES -> {
                navController.navigate(AppRoute.ActiveDevices) {
                    launchSingleTop = true
                }
            }
            AppNotificationManager.NAV_NOTIFICATIONS -> {
                navController.navigate(AppRoute.Notifications) {
                    launchSingleTop = true
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        container.securityAlerts.collect { alert ->
            if (alert.alertType == "account_deleted" ||
                alert.alertType == "session_revoked" ||
                alert.alertType == "logout_all_devices"
            ) {
                val msg = alert.body.ifBlank { alert.title }.ifBlank {
                    context.getString(R.string.err_session_revoked)
                }
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                navController.navigate(AppRoute.Welcome) {
                    popUpTo(0) { inclusive = true }
                }
            }
        }
    }

    LaunchedEffect(incomingCall) {
        val invite = incomingCall ?: return@LaunchedEffect
        // Consume navigation prompt ONLY — ringtone keeps playing until Accept, Reject, or Disconnect
        container.consumeIncomingCallPrompt()
        if (activeCallScreenId == invite.callId) {
            return@LaunchedEffect
        }
        navController.navigate(
            AppRoute.Call(
                callId = invite.callId,
                conversationId = invite.conversationId,
                peerUserId = invite.callerId,
                peerName = invite.callerName,
                peerAvatarMediaId = invite.callerAvatarMediaId,
                isVideo = invite.isVideo,
                isIncoming = true,
                autoAccept = false
            )
        ) {
            launchSingleTop = true
        }
    }

    LaunchedEffect(pendingCallLaunch) {
        val launch = pendingCallLaunch ?: return@LaunchedEffect
        onCallLaunchConsumed()
        if (activeCallScreenId == launch.callId) {
            if (launch.autoAccept) {
                container.requestExternalCallAccept(launch.callId)
            }
            return@LaunchedEffect
        }
        navController.navigate(
            AppRoute.Call(
                callId = launch.callId,
                conversationId = launch.conversationId,
                peerUserId = launch.peerUserId,
                peerName = launch.peerName,
                peerAvatarMediaId = launch.peerAvatarMediaId,
                isVideo = launch.isVideo,
                isIncoming = true,
                autoAccept = launch.autoAccept
            )
        ) {
            launchSingleTop = true
        }
    }

    val authVm: AuthViewModel = viewModel(
        factory = singleVmFactory {
            AuthViewModel(
                authRepository = container.authRepository,
                prefsStore = container.prefsStore,
                simVerifier = container.simVerificationProvider
            )
        }
    )

    NavHost(
        navController = navController,
        startDestination = AppRoute.Splash
    ) {
        composable<AppRoute.Splash> {
            SplashScreen(
                tokenStore = container.tokenStore,
                onAuthenticated = {
                    navController.navigate(AppRoute.MainTabs) {
                        popUpTo(AppRoute.Splash) { inclusive = true }
                    }
                },
                onUnauthenticated = {
                    navController.navigate(AppRoute.Welcome) {
                        popUpTo(AppRoute.Splash) { inclusive = true }
                    }
                }
            )
        }

        composable<AppRoute.Welcome> {
            val prefs by container.prefsStore.preferencesFlow.collectAsStateWithLifecycle(
                initialValue = AppPreferences()
            )
            WelcomeScreen(
                currentLanguage = prefs.language,
                onLanguageSelected = { authVm.setLanguage(it) },
                onNavigateToLogin = {
                    authVm.resetFlow()
                    navController.navigate(AppRoute.Login)
                },
                onNavigateToCreateAccount = {
                    authVm.resetFlow()
                    navController.navigate(AppRoute.CreateAccount)
                }
            )
        }

        composable<AppRoute.Login> {
            LoginScreen(
                viewModel = authVm,
                onBack = { navController.popBackStack() },
                onLoginSuccess = {
                    navController.navigate(AppRoute.MainTabs) {
                        popUpTo(AppRoute.Welcome) { inclusive = true }
                    }
                },
                onSwitchToCreateAccount = {
                    authVm.resetFlow()
                    navController.navigate(AppRoute.CreateAccount) {
                        popUpTo(AppRoute.Login) { inclusive = true }
                    }
                },
                onCreateAccountWithPhone = {
                    authVm.prepareCreateFromLogin()
                    navController.navigate(AppRoute.CreateAccount) {
                        popUpTo(AppRoute.Login) { inclusive = true }
                    }
                },
                onForgotPin = {
                    navController.navigate(AppRoute.ForgotLoginPin)
                }
            )
        }

        composable<AppRoute.CreateAccount> {
            CreateAccountScreen(
                viewModel = authVm,
                onBack = { navController.popBackStack() },
                onAccountCreated = {
                    navController.navigate(AppRoute.MainTabs) {
                        popUpTo(AppRoute.Welcome) { inclusive = true }
                    }
                },
                onSwitchToLogin = {
                    authVm.resetFlow()
                    navController.navigate(AppRoute.Login) {
                        popUpTo(AppRoute.CreateAccount) { inclusive = true }
                    }
                }
            )
        }

        composable<AppRoute.MainTabs> {
            val chatsVm: ChatsViewModel = viewModel(
                factory = singleVmFactory {
                    ChatsViewModel(
                        chatRepository = container.chatRepository,
                        tokenStore = container.tokenStore,
                        prefsStore = container.prefsStore,
                        networkMonitor = container.networkModule.networkMonitor,
                        socketManager = container.networkModule.realtimeSocketManager,
                        safetyRepository = container.safetyRepository,
                        authRepository = container.authRepository
                    )
                }
            )
            val settingsVm: SettingsViewModel = viewModel(
                factory = singleVmFactory {
                    SettingsViewModel(
                        appContext = container.appContext,
                        prefsStore = container.prefsStore,
                        tokenStore = container.tokenStore,
                        authRepository = container.authRepository,
                        safetyRepository = container.safetyRepository,
                        ownerRepository = container.ownerRepository,
                        privateChatRepository = container.privateChatRepository,
                        appUpdateChecker = container.appUpdateChecker
                    )
                }
            )

            MainTabsScreen(
                chatsViewModel = chatsVm,
                settingsViewModel = settingsVm,
                networkModule = container.networkModule,
                interstitialAdManager = container.interstitialAdManager,
                onOpenProfile = { navController.navigate(AppRoute.Profile) },
                onOpenConversation = { conv ->
                    navController.navigate(
                        AppRoute.ChatDetail(
                            conversationId = conv.id,
                            peerUserId = conv.peerUserId,
                            peerName = conv.peerDisplayName,
                            isPrivate = false
                        )
                    )
                },
                onOpenBlockedUsers = { navController.navigate(AppRoute.BlockedUsers) },
                onOpenActiveDevices = { navController.navigate(AppRoute.ActiveDevices) },
                onOpenOwnerMonitor = { navController.navigate(AppRoute.OwnerActivity) },
                onOpenPrivateChat = { navController.navigate(AppRoute.PrivatePin) },
                onOpenNotifications = { navController.navigate(AppRoute.Notifications) },
                onOpenCallHistory = { navController.navigate(AppRoute.CallHistory) },
                onOpenNotificationSettings = { navController.navigate(AppRoute.NotificationSettings) },
                onOpenChangeLoginPin = { navController.navigate(AppRoute.ChangeLoginPin) },
                onOpenRecoveryCode = { navController.navigate(AppRoute.RecoveryCode) },
                onOpenForgotLoginPin = { navController.navigate(AppRoute.ForgotLoginPin) },
                onLoggedOutOrDeleted = {
                    navController.navigate(AppRoute.Welcome) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        composable<AppRoute.ChatDetail> { backStackEntry ->
            val route = backStackEntry.toRoute<AppRoute.ChatDetail>()
            DisposableEffect(route.conversationId) {
                container.activeConversationId = route.conversationId
                onDispose {
                    if (container.activeConversationId == route.conversationId) {
                        container.activeConversationId = null
                    }
                }
            }

            val detailVm: ChatDetailViewModel = viewModel(
                key = "chat_detail_${route.conversationId}_${route.isPrivate}",
                factory = singleVmFactory {
                    ChatDetailViewModel(
                        context = container.appContext,
                        conversationId = route.conversationId,
                        peerUserId = route.peerUserId,
                        chatRepository = container.chatRepository,
                        messageRepository = container.messageRepository,
                        safetyRepository = container.safetyRepository,
                        tokenStore = container.tokenStore,
                        prefsStore = container.prefsStore,
                        socketManager = container.networkModule.realtimeSocketManager,
                        okHttpClient = container.networkModule.okHttpClient,
                        networkMonitor = container.networkModule.networkMonitor,
                        isPrivate = route.isPrivate,
                        privateChatRepository = container.privateChatRepository,
                        callRepository = container.callRepository,
                        baseUrl = container.networkModule.baseUrl
                    )
                }
            )

            val conv by detailVm.conversation.collectAsStateWithLifecycle()

            ChatDetailScreen(
                viewModel = detailVm,
                initialPeerName = route.peerName,
                networkModule = container.networkModule,
                onBack = { navController.popBackStack() },
                onStartAudioCall = {
                    navController.navigate(
                        AppRoute.Call(
                            callId = UUID.randomUUID().toString(),
                            conversationId = route.conversationId,
                            peerUserId = route.peerUserId,
                            peerName = conv?.peerDisplayName ?: route.peerName,
                            peerAvatarMediaId = conv?.peerAvatarMediaId,
                            isVideo = false,
                            isIncoming = false
                        )
                    )
                },
                onStartVideoCall = {
                    navController.navigate(
                        AppRoute.Call(
                            callId = UUID.randomUUID().toString(),
                            conversationId = route.conversationId,
                            peerUserId = route.peerUserId,
                            peerName = conv?.peerDisplayName ?: route.peerName,
                            peerAvatarMediaId = conv?.peerAvatarMediaId,
                            isVideo = true,
                            isIncoming = false
                        )
                    )
                },
                onOpenMediaViewer = { messageId, mediaId, mimeType, filename, isVideo, isMine ->
                    navController.navigate(
                        AppRoute.MediaViewer(
                            mediaId = mediaId,
                            mimeType = mimeType,
                            filename = filename,
                            isVideo = isVideo,
                            messageId = messageId,
                            isMine = isMine,
                            isPrivate = route.isPrivate
                        )
                    )
                },
                onPrivateLockedOut = {
                    navController.navigate(AppRoute.PrivatePin) {
                        launchSingleTop = true
                        popUpTo(AppRoute.MainTabs) { inclusive = false }
                    }
                }
            )
        }

        composable<AppRoute.PrivatePin> {
            val privateVm: PrivateChatViewModel = viewModel(
                factory = singleVmFactory {
                    PrivateChatViewModel(
                        privateChatRepository = container.privateChatRepository,
                        tokenStore = container.tokenStore,
                        prefsStore = container.prefsStore,
                        networkMonitor = container.networkModule.networkMonitor,
                        socketManager = container.networkModule.realtimeSocketManager,
                        safetyRepository = container.safetyRepository
                    )
                }
            )
            PrivatePinScreen(
                viewModel = privateVm,
                onBack = { navController.popBackStack() },
                onUnlocked = {
                    navController.navigate(AppRoute.PrivateChats) {
                        popUpTo(AppRoute.PrivatePin) { inclusive = true }
                    }
                }
            )
        }

        composable<AppRoute.PrivateChats> {
            val privateVm: PrivateChatViewModel = viewModel(
                factory = singleVmFactory {
                    PrivateChatViewModel(
                        privateChatRepository = container.privateChatRepository,
                        tokenStore = container.tokenStore,
                        prefsStore = container.prefsStore,
                        networkMonitor = container.networkModule.networkMonitor,
                        socketManager = container.networkModule.realtimeSocketManager,
                        safetyRepository = container.safetyRepository
                    )
                }
            )
            PrivateChatsScreen(
                viewModel = privateVm,
                networkModule = container.networkModule,
                onBackAndLock = {
                    privateVm.lockNow()
                    navController.popBackStack()
                },
                onLockedOut = {
                    navController.navigate(AppRoute.PrivatePin) {
                        launchSingleTop = true
                        popUpTo(AppRoute.MainTabs) { inclusive = false }
                    }
                },
                onOpenPrivateConversation = { conv ->
                    navController.navigate(
                        AppRoute.ChatDetail(
                            conversationId = conv.id,
                            peerUserId = conv.peerUserId,
                            peerName = conv.peerDisplayName,
                            isPrivate = true
                        )
                    )
                }
            )
        }

        composable<AppRoute.Profile> {
            val profileVm: ProfileViewModel = viewModel(
                factory = singleVmFactory {
                    ProfileViewModel(container.profileRepository, container.tokenStore)
                }
            )
            ProfileScreen(
                viewModel = profileVm,
                networkModule = container.networkModule,
                onBack = { navController.popBackStack() }
            )
        }

        composable<AppRoute.CallHistory> {
            val callHistoryVm: CallHistoryViewModel = viewModel(
                factory = singleVmFactory {
                    CallHistoryViewModel(container.callRepository, container.chatRepository)
                }
            )
            CallHistoryScreen(
                viewModel = callHistoryVm,
                networkModule = container.networkModule,
                onBack = { navController.popBackStack() },
                onStartCallback = { item, isVideo ->
                    navController.navigate(
                        AppRoute.Call(
                            callId = UUID.randomUUID().toString(),
                            conversationId = item.conversationId,
                            peerUserId = item.peerUserId,
                            peerName = item.peerDisplayName,
                            peerAvatarMediaId = item.peerAvatarMediaId,
                            isVideo = isVideo,
                            isIncoming = false
                        )
                    )
                }
            )
        }

        composable<AppRoute.Notifications> {
            NotificationsScreen(
                authRepository = container.authRepository,
                prefsStore = container.prefsStore,
                onBack = { navController.popBackStack() },
                onOpenActiveDevices = { navController.navigate(AppRoute.ActiveDevices) }
            )
        }

        composable<AppRoute.NotificationSettings> {
            val settingsVm = rememberSettingsVm(container)
            NotificationSettingsScreen(
                viewModel = settingsVm,
                onBack = { navController.popBackStack() }
            )
        }

        composable<AppRoute.ChangeLoginPin> {
            val settingsVm = rememberSettingsVm(container)
            ChangeLoginPinScreen(
                viewModel = settingsVm,
                onBack = { navController.popBackStack() }
            )
        }

        composable<AppRoute.RecoveryCode> {
            val settingsVm = rememberSettingsVm(container)
            RecoveryCodeScreen(
                viewModel = settingsVm,
                onBack = { navController.popBackStack() }
            )
        }

        composable<AppRoute.ForgotLoginPin> {
            val settingsVm = rememberSettingsVm(container)
            ForgotLoginPinScreen(
                viewModel = settingsVm,
                onBack = { navController.popBackStack() },
                onResetSuccess = { navController.popBackStack() }
            )
        }

        composable<AppRoute.BlockedUsers> {
            val settingsVm = rememberSettingsVm(container)
            BlockedUsersScreen(
                viewModel = settingsVm,
                networkModule = container.networkModule,
                onBack = { navController.popBackStack() }
            )
        }

        composable<AppRoute.ActiveDevices> {
            val settingsVm = rememberSettingsVm(container)
            ActiveDevicesScreen(
                viewModel = settingsVm,
                onBack = { navController.popBackStack() },
                onLoggedOutAllDevices = {
                    navController.navigate(AppRoute.Welcome) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        composable<AppRoute.OwnerActivity> {
            val settingsVm = rememberSettingsVm(container)
            OwnerActivityScreen(
                viewModel = settingsVm,
                onBack = { navController.popBackStack() }
            )
        }

        composable<AppRoute.Call> { backStackEntry ->
            val route = backStackEntry.toRoute<AppRoute.Call>()
            val callContext = LocalContext.current
            val activity = remember(callContext) { callContext.findActivity() }
            DisposableEffect(route.callId) {
                activeCallScreenId = route.callId
                onDispose {
                    if (activeCallScreenId == route.callId) {
                        activeCallScreenId = null
                    }
                }
            }
            DisposableEffect(activity) {
                if (activity != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                        activity.setShowWhenLocked(true)
                        activity.setTurnScreenOn(true)
                    } else {
                        @Suppress("DEPRECATION")
                        activity.window.addFlags(
                            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                        )
                    }
                }
                onDispose {
                    if (activity != null) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                            activity.setShowWhenLocked(false)
                            activity.setTurnScreenOn(false)
                        } else {
                            @Suppress("DEPRECATION")
                            activity.window.clearFlags(
                                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                            )
                        }
                    }
                }
            }
            LaunchedEffect(Unit) {
                container.interstitialAdManager.preload(resetRetry = true)
            }
            val callVm: CallViewModel = viewModel(
                key = "call_${route.callId}_${route.isIncoming}",
                factory = singleVmFactory {
                    CallViewModel(
                        context = container.appContext,
                        initialCallId = if (route.isIncoming) route.callId else "",
                        conversationId = route.conversationId,
                        peerUserId = route.peerUserId,
                        isVideo = route.isVideo,
                        isIncoming = route.isIncoming,
                        callRepository = container.callRepository,
                        socketManager = container.networkModule.realtimeSocketManager,
                        networkMonitor = container.networkModule.networkMonitor,
                        autoAccept = route.autoAccept,
                        onStopIncomingAlert = { cid ->
                            container.stopIncomingCallAlert(cid.ifBlank { route.callId })
                        },
                        chatRepository = container.chatRepository
                    )
                }
            )
            LaunchedEffect(route.callId, callVm) {
                container.externalCallAccept.collect { acceptedCallId ->
                    if (acceptedCallId == route.callId) {
                        callVm.acceptFromExternalTrigger()
                    }
                }
            }
            CallScreen(
                viewModel = callVm,
                peerName = route.peerName,
                peerAvatarMediaId = route.peerAvatarMediaId,
                networkModule = container.networkModule,
                onExitCall = {
                    container.stopIncomingCallAlert(route.callId)
                    container.interstitialAdManager.showThen(activity) {
                        navController.popBackStack()
                    }
                }
            )
        }

        composable<AppRoute.MediaViewer> { backStackEntry ->
            val route = backStackEntry.toRoute<AppRoute.MediaViewer>()
            MediaViewerScreen(
                mediaId = route.mediaId,
                filename = route.filename,
                isVideo = route.isVideo,
                messageId = route.messageId,
                isMine = route.isMine,
                isPrivate = route.isPrivate,
                networkModule = container.networkModule,
                messageRepository = container.messageRepository,
                privateChatRepository = container.privateChatRepository,
                onBack = { navController.popBackStack() }
            )
        }
    }
}

@Composable
private fun rememberSettingsVm(container: AppContainer): SettingsViewModel {
    return viewModel(
        factory = singleVmFactory {
            SettingsViewModel(
                appContext = container.appContext,
                prefsStore = container.prefsStore,
                tokenStore = container.tokenStore,
                authRepository = container.authRepository,
                safetyRepository = container.safetyRepository,
                ownerRepository = container.ownerRepository,
                privateChatRepository = container.privateChatRepository,
                appUpdateChecker = container.appUpdateChecker
            )
        }
    )
}

@Composable
private fun <T : ViewModel> singleVmFactory(creator: () -> T): ViewModelProvider.Factory {
    return rememberFactory(creator)
}

@Composable
private fun <T : ViewModel> rememberFactory(creator: () -> T): ViewModelProvider.Factory {
    return remember {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <VM : ViewModel> create(modelClass: Class<VM>): VM {
                return creator() as VM
            }
        }
    }
}
