package com.mychattingpro.features.chats

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.PersonAddAlt
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.R
import com.mychattingpro.core.network.NetworkModule
import com.mychattingpro.domain.models.Conversation
import com.mychattingpro.features.ads.InterstitialAdManager
import com.mychattingpro.features.settings.SettingsScreen
import com.mychattingpro.features.settings.SettingsViewModel

@Composable
fun MainTabsScreen(
    chatsViewModel: ChatsViewModel,
    settingsViewModel: SettingsViewModel,
    networkModule: NetworkModule,
    interstitialAdManager: InterstitialAdManager,
    onOpenProfile: () -> Unit,
    onOpenConversation: (Conversation) -> Unit,
    onOpenBlockedUsers: () -> Unit,
    onOpenActiveDevices: () -> Unit,
    onOpenOwnerMonitor: () -> Unit,
    onOpenPrivateChat: () -> Unit = {},
    onOpenCallHistory: () -> Unit = {},
    onOpenNotificationSettings: () -> Unit = {},
    onOpenChangeLoginPin: () -> Unit = {},
    onOpenRecoveryCode: () -> Unit = {},
    onOpenForgotLoginPin: () -> Unit = {},
    onLoggedOutOrDeleted: () -> Unit
) {
    // Strictly ONLY 3 options: 1. Chats, 2. Add Chat, 3. Settings
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var askedNotificationPermission by rememberSaveable { mutableIntStateOf(0) }
    val context = LocalContext.current
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(Unit) {
        interstitialAdManager.preload()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && askedNotificationPermission == 0) {
            askedNotificationPermission = 1
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    val tabItems = listOf(
        Triple(stringResource(R.string.nav_chats), Icons.Rounded.ChatBubble, Icons.Outlined.ChatBubbleOutline),
        Triple(stringResource(R.string.nav_add_chat), Icons.Rounded.PersonAdd, Icons.Outlined.PersonAddAlt),
        Triple(stringResource(R.string.nav_settings), Icons.Rounded.Settings, Icons.Outlined.Settings)
    )

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 600.dp

        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                if (!useRail) {
                    NavigationBar {
                        tabItems.forEachIndexed { index, (label, filledIcon, outlinedIcon) ->
                            val selected = selectedTab == index
                            NavigationBarItem(
                                selected = selected,
                                onClick = { selectedTab = index },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) filledIcon else outlinedIcon,
                                        contentDescription = label
                                    )
                                },
                                label = { Text(label) },
                                modifier = Modifier.testTag("bottom_nav_$index")
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useRail) {
                    NavigationRail {
                        tabItems.forEachIndexed { index, (label, filledIcon, outlinedIcon) ->
                            val selected = selectedTab == index
                            NavigationRailItem(
                                selected = selected,
                                onClick = { selectedTab = index },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) filledIcon else outlinedIcon,
                                        contentDescription = label
                                    )
                                },
                                label = { Text(label) },
                                modifier = Modifier.testTag("rail_nav_$index")
                            )
                        }
                    }
                }

                when (selectedTab) {
                    0 -> ChatsScreen(
                        viewModel = chatsViewModel,
                        networkModule = networkModule,
                        onOpenProfile = onOpenProfile,
                        onOpenConversation = onOpenConversation,
                        onOpenPrivateChat = onOpenPrivateChat,
                        onOpenOwnerMonitor = onOpenOwnerMonitor,
                        onOpenCallHistory = onOpenCallHistory
                    )
                    1 -> AddChatScreen(
                        viewModel = chatsViewModel,
                        networkModule = networkModule,
                        onConversationReady = onOpenConversation
                    )
                    else -> SettingsScreen(
                        viewModel = settingsViewModel,
                        networkModule = networkModule,
                        interstitialAdManager = interstitialAdManager,
                        onOpenProfile = onOpenProfile,
                        onOpenNotificationSettings = onOpenNotificationSettings,
                        onOpenBlockedUsers = onOpenBlockedUsers,
                        onOpenActiveDevices = onOpenActiveDevices,
                        onOpenChangeLoginPin = onOpenChangeLoginPin,
                        onOpenRecoveryCode = onOpenRecoveryCode,
                        onOpenForgotLoginPin = onOpenForgotLoginPin,
                        onLoggedOutOrDeleted = onLoggedOutOrDeleted
                    )
                }
            }
        }
    }
}
