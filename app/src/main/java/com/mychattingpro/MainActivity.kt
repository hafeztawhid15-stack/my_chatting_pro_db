package com.mychattingpro

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
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
import com.mychattingpro.features.calls.CallScreen
import com.mychattingpro.features.calls.CallViewModel
import com.mychattingpro.features.chats.ChatsViewModel
import com.mychattingpro.features.chats.MainTabsScreen
import com.mychattingpro.features.messages.ChatDetailScreen
import com.mychattingpro.features.messages.ChatDetailViewModel
import com.mychattingpro.features.messages.MediaViewerScreen
import com.mychattingpro.features.notifications.RealtimeSyncService
import com.mychattingpro.features.profile.ProfileScreen
import com.mychattingpro.features.profile.ProfileViewModel
import com.mychattingpro.features.settings.ActiveDevicesScreen
import com.mychattingpro.features.settings.BlockedUsersScreen
import com.mychattingpro.features.settings.OwnerActivityScreen
import com.mychattingpro.features.settings.SettingsViewModel
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as MyChattingProApp
        val container = app.container

        setContent {
            val prefs by container.prefsStore.preferencesFlow.collectAsStateWithLifecycle(
                initialValue = AppPreferences()
            )
            val session by container.tokenStore.sessionFlow.collectAsStateWithLifecycle(
                initialValue = null
            )

            LaunchedEffect(prefs.backgroundSyncEnabled, session?.userId) {
                if (prefs.backgroundSyncEnabled && session != null) {
                    RealtimeSyncService.start(applicationContext)
                } else if (!prefs.backgroundSyncEnabled) {
                    RealtimeSyncService.stop(applicationContext)
                }
            }

            ProvideAppLocalization(language = prefs.language) {
                MyChattingProTheme(themePreference = prefs.themePreference) {
                    MyChattingProNavRoot(container = container)
                }
            }
        }
    }
}

@Composable
private fun MyChattingProNavRoot(container: AppContainer) {
    val navController = rememberNavController()
    val incomingCall by container.incomingCall.collectAsStateWithLifecycle()

    LaunchedEffect(incomingCall) {
        val invite = incomingCall ?: return@LaunchedEffect
        container.clearIncomingCall()
        navController.navigate(
            AppRoute.Call(
                callId = invite.callId,
                conversationId = invite.conversationId,
                peerUserId = invite.callerId,
                peerName = invite.callerName,
                peerAvatarMediaId = invite.callerAvatarMediaId,
                isVideo = invite.isVideo,
                isIncoming = true
            )
        )
    }

    val authVm: AuthViewModel = viewModel(
        factory = singleVmFactory {
            AuthViewModel(container.authRepository, container.prefsStore)
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
                        socketManager = container.networkModule.realtimeSocketManager
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
                        ownerRepository = container.ownerRepository
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
                            peerName = conv.peerDisplayName
                        )
                    )
                },
                onOpenBlockedUsers = { navController.navigate(AppRoute.BlockedUsers) },
                onOpenActiveDevices = { navController.navigate(AppRoute.ActiveDevices) },
                onOpenOwnerMonitor = { navController.navigate(AppRoute.OwnerActivity) },
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
                key = "chat_detail_${route.conversationId}",
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
                        networkMonitor = container.networkModule.networkMonitor
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
                onOpenMediaViewer = { mediaId, mimeType, filename, isVideo ->
                    navController.navigate(
                        AppRoute.MediaViewer(
                            mediaId = mediaId,
                            mimeType = mimeType,
                            filename = filename,
                            isVideo = isVideo
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

        composable<AppRoute.BlockedUsers> {
            val settingsVm: SettingsViewModel = viewModel(
                factory = singleVmFactory {
                    SettingsViewModel(
                        appContext = container.appContext,
                        prefsStore = container.prefsStore,
                        tokenStore = container.tokenStore,
                        authRepository = container.authRepository,
                        safetyRepository = container.safetyRepository,
                        ownerRepository = container.ownerRepository
                    )
                }
            )
            BlockedUsersScreen(
                viewModel = settingsVm,
                networkModule = container.networkModule,
                onBack = { navController.popBackStack() }
            )
        }

        composable<AppRoute.ActiveDevices> {
            val settingsVm: SettingsViewModel = viewModel(
                factory = singleVmFactory {
                    SettingsViewModel(
                        appContext = container.appContext,
                        prefsStore = container.prefsStore,
                        tokenStore = container.tokenStore,
                        authRepository = container.authRepository,
                        safetyRepository = container.safetyRepository,
                        ownerRepository = container.ownerRepository
                    )
                }
            )
            ActiveDevicesScreen(
                viewModel = settingsVm,
                onBack = { navController.popBackStack() }
            )
        }

        composable<AppRoute.OwnerActivity> {
            val settingsVm: SettingsViewModel = viewModel(
                factory = singleVmFactory {
                    SettingsViewModel(
                        appContext = container.appContext,
                        prefsStore = container.prefsStore,
                        tokenStore = container.tokenStore,
                        authRepository = container.authRepository,
                        safetyRepository = container.safetyRepository,
                        ownerRepository = container.ownerRepository
                    )
                }
            )
            OwnerActivityScreen(
                viewModel = settingsVm,
                onBack = { navController.popBackStack() }
            )
        }

        composable<AppRoute.Call> { backStackEntry ->
            val route = backStackEntry.toRoute<AppRoute.Call>()
            val context = LocalContext.current
            val activity = remember(context) { context.findActivity() }
            LaunchedEffect(Unit) {
                container.interstitialAdManager.preload()
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
                        socketManager = container.networkModule.realtimeSocketManager
                    )
                }
            )
            CallScreen(
                viewModel = callVm,
                peerName = route.peerName,
                peerAvatarMediaId = route.peerAvatarMediaId,
                networkModule = container.networkModule,
                onExitCall = {
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
                networkModule = container.networkModule,
                messageRepository = container.messageRepository,
                onBack = { navController.popBackStack() }
            )
        }
    }
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
