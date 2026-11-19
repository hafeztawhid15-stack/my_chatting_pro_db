package com.mychattingpro.features.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Policy
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.mychattingpro.core.localization.AppLanguage
import com.mychattingpro.core.network.NetworkModule
import com.mychattingpro.core.theme.ThemePreference
import com.mychattingpro.features.ads.InterstitialAdManager
import com.mychattingpro.features.ads.findActivity
import com.mychattingpro.features.common.FiveDigitPinField
import com.mychattingpro.features.common.UserAvatar

private const val YOUTUBE_URL = "https://www.youtube.com/channel/UClRY5eaWzQbXG2y2E5xcYdg"
private const val FACEBOOK_URL = "https://www.facebook.com/profile.php?id=61584997594783"
private const val PRIVACY_POLICY_URL = "https://sites.google.com/view/sindha/%E0%A6%B9%E0%A6%AE"
private const val FEEDBACK_EMAIL = "hafeztawhid15@gmail.com"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    networkModule: NetworkModule,
    interstitialAdManager: InterstitialAdManager,
    onOpenProfile: () -> Unit,
    onOpenBlockedUsers: () -> Unit,
    onOpenActiveDevices: () -> Unit,
    onOpenOwnerMonitor: () -> Unit,
    onLoggedOutOrDeleted: () -> Unit
) {
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }

    LaunchedEffect(Unit) {
        interstitialAdManager.preload()
    }

    fun withSettingsAd(action: () -> Unit) {
        interstitialAdManager.showThen(activity, action)
    }

    var showLanguageDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showVersionDialog by remember { mutableStateOf(false) }
    var showLogoutDialog by remember { mutableStateOf(false) }
    var showDeleteAccountDialog by remember { mutableStateOf(false) }
    var deletePin by remember { mutableStateOf("") }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.setBackgroundSyncEnabled(true)
        } else {
            Toast.makeText(context, context.getString(R.string.perm_denied), Toast.LENGTH_LONG).show()
            viewModel.setBackgroundSyncEnabled(false)
        }
    }

    // Read Version Name & Version Code dynamically from PackageManager
    val appVersionInfo = remember(context) {
        runCatching {
            val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(0L)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            val name = pkgInfo.versionName ?: "1.0.0"
            val code = PackageInfoCompat.getLongVersionCode(pkgInfo)
            name to code
        }.getOrElse { "1.0.0" to 1L }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.nav_settings),
                        fontWeight = FontWeight.Bold
                    )
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                Spacer(modifier = Modifier.height(4.dp))
                // Profile Card
                ElevatedCard(
                    onClick = { withSettingsAd(onOpenProfile) },
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 600.dp)
                        .testTag("settings_profile_card")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        UserAvatar(
                            displayName = session?.displayName ?: stringResource(R.string.app_name),
                            avatarMediaId = session?.avatarMediaId,
                            networkModule = networkModule,
                            size = 56.dp
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = session?.displayName ?: stringResource(R.string.app_name),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = session?.about?.ifBlank { stringResource(R.string.hint_about) }
                                    ?: stringResource(R.string.settings_profile),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (!session?.phoneE164.isNullOrBlank()) {
                                Text(
                                    text = session?.phoneE164.orEmpty(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            contentDescription = stringResource(R.string.settings_profile),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item {
                // Non-editable Owner / Developer Badge
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 600.dp)
                        .testTag("settings_owner_badge")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_launcher_art_clean),
                            contentDescription = stringResource(R.string.app_name),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_owner_label),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            )
                            Text(
                                text = stringResource(R.string.settings_owner_name),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        Icon(
                            imageVector = Icons.Rounded.VerifiedUser,
                            contentDescription = stringResource(R.string.settings_owner_label),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            item {
                // Preferences Group
                ElevatedCard(
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 600.dp)
                ) {
                    Column {
                        SettingsRowItem(
                            icon = Icons.Outlined.Person,
                            title = stringResource(R.string.settings_profile),
                            subtitle = session?.displayName,
                            onClick = { withSettingsAd(onOpenProfile) },
                            testTag = "settings_edit_profile"
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        SettingsRowItem(
                            icon = Icons.Outlined.Language,
                            title = stringResource(R.string.settings_language),
                            subtitle = prefs.language.nativeLabel,
                            onClick = { withSettingsAd { showLanguageDialog = true } },
                            testTag = "settings_language"
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        SettingsRowItem(
                            icon = Icons.Outlined.DarkMode,
                            title = stringResource(R.string.settings_theme),
                            subtitle = when (prefs.themePreference) {
                                ThemePreference.SYSTEM -> stringResource(R.string.theme_system)
                                ThemePreference.LIGHT -> stringResource(R.string.theme_light)
                                ThemePreference.DARK -> stringResource(R.string.theme_dark)
                            },
                            onClick = { withSettingsAd { showThemeDialog = true } },
                            testTag = "settings_theme"
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                                .testTag("settings_bg_sync_row"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Sync,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.settings_bg_sync),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = stringResource(R.string.settings_bg_sync_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Switch(
                                checked = prefs.backgroundSyncEnabled,
                                onCheckedChange = { enable ->
                                    withSettingsAd {
                                        if (!enable) {
                                            viewModel.setBackgroundSyncEnabled(false)
                                        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                            ContextCompat.checkSelfPermission(
                                                context,
                                                Manifest.permission.POST_NOTIFICATIONS
                                            ) != PackageManager.PERMISSION_GRANTED
                                        ) {
                                            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                        } else {
                                            viewModel.setBackgroundSyncEnabled(true)
                                        }
                                    }
                                },
                                modifier = Modifier.testTag("settings_bg_sync_switch")
                            )
                        }
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        SettingsRowItem(
                            icon = Icons.Outlined.Devices,
                            title = stringResource(R.string.settings_active_devices),
                            subtitle = stringResource(R.string.settings_active_devices_desc),
                            onClick = { withSettingsAd(onOpenActiveDevices) },
                            testTag = "settings_active_devices"
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        SettingsRowItem(
                            icon = Icons.Rounded.Shield,
                            title = stringResource(R.string.settings_owner_monitor),
                            subtitle = stringResource(R.string.settings_owner_monitor_desc),
                            onClick = { withSettingsAd(onOpenOwnerMonitor) },
                            testTag = "settings_owner_monitor"
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        SettingsRowItem(
                            icon = Icons.Outlined.Block,
                            title = stringResource(R.string.settings_blocked_users),
                            onClick = { withSettingsAd(onOpenBlockedUsers) },
                            testTag = "settings_blocked_users"
                        )
                    }
                }
            }

            item {
                // External Links, Feedback, Legal, Version
                ElevatedCard(
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 600.dp)
                ) {
                    Column {
                        SettingsRowItem(
                            icon = Icons.Outlined.PlayCircle,
                            title = stringResource(R.string.settings_youtube_label),
                            subtitle = stringResource(R.string.settings_youtube_subtitle),
                            onClick = { withSettingsAd { openExternalUrl(context, YOUTUBE_URL) } },
                            testTag = "settings_youtube"
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        SettingsRowItem(
                            icon = Icons.Outlined.Public,
                            title = stringResource(R.string.settings_facebook),
                            onClick = { withSettingsAd { openExternalUrl(context, FACEBOOK_URL) } },
                            testTag = "settings_facebook"
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        SettingsRowItem(
                            icon = Icons.Outlined.Email,
                            title = stringResource(R.string.settings_feedback),
                            subtitle = FEEDBACK_EMAIL,
                            onClick = { withSettingsAd { openFeedbackEmail(context) } },
                            testTag = "settings_feedback"
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        SettingsRowItem(
                            icon = Icons.Outlined.Policy,
                            title = stringResource(R.string.settings_privacy_policy),
                            onClick = { withSettingsAd { openExternalUrl(context, PRIVACY_POLICY_URL) } },
                            testTag = "settings_privacy_policy"
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        SettingsRowItem(
                            icon = Icons.Outlined.Info,
                            title = stringResource(R.string.settings_version),
                            subtitle = "v${appVersionInfo.first} (${appVersionInfo.second})",
                            onClick = { withSettingsAd { showVersionDialog = true } },
                            testTag = "settings_version"
                        )
                    }
                }
            }

            item {
                // Account Actions
                ElevatedCard(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.elevatedCardColors(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 600.dp)
                ) {
                    Column {
                        SettingsRowItem(
                            icon = Icons.AutoMirrored.Rounded.Logout,
                            title = stringResource(R.string.action_logout),
                            onClick = { withSettingsAd { showLogoutDialog = true } },
                            testTag = "settings_logout"
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        SettingsRowItem(
                            icon = Icons.Outlined.DeleteForever,
                            title = stringResource(R.string.settings_delete_account),
                            isDestructive = true,
                            onClick = {
                                withSettingsAd {
                                    deletePin = ""
                                    viewModel.clearError()
                                    showDeleteAccountDialog = true
                                }
                            },
                            testTag = "settings_delete_account"
                        )
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }

    if (showLanguageDialog) {
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            title = { Text(stringResource(R.string.settings_language)) },
            text = {
                Column {
                    AppLanguage.entries.forEach { lang ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.setLanguage(lang)
                                    showLanguageDialog = false
                                }
                                .padding(vertical = 10.dp)
                                .testTag("language_option_${lang.code}"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = prefs.language == lang,
                                onClick = {
                                    viewModel.setLanguage(lang)
                                    showLanguageDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = lang.nativeLabel,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLanguageDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (showThemeDialog) {
        val options = listOf(
            ThemePreference.SYSTEM to stringResource(R.string.theme_system),
            ThemePreference.LIGHT to stringResource(R.string.theme_light),
            ThemePreference.DARK to stringResource(R.string.theme_dark)
        )
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            title = { Text(stringResource(R.string.settings_theme)) },
            text = {
                Column {
                    options.forEach { (theme, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.setThemePreference(theme)
                                    showThemeDialog = false
                                }
                                .padding(vertical = 10.dp)
                                .testTag("theme_option_${theme.name.lowercase()}"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = prefs.themePreference == theme,
                                onClick = {
                                    viewModel.setThemePreference(theme)
                                    showThemeDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showThemeDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (showVersionDialog) {
        AlertDialog(
            onDismissRequest = { showVersionDialog = false },
            title = { Text(stringResource(R.string.version_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.version_name_label, appVersionInfo.first),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = stringResource(R.string.version_code_label, appVersionInfo.second),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${stringResource(R.string.settings_owner_label)}: ${stringResource(R.string.settings_owner_name)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showVersionDialog = false }) {
                    Text(stringResource(R.string.action_confirm))
                }
            }
        )
    }

    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { if (!uiState.isLoggingOut) showLogoutDialog = false },
            title = { Text(stringResource(R.string.action_logout)) },
            text = { Text(stringResource(R.string.app_tagline)) },
            confirmButton = {
                TextButton(
                    enabled = !uiState.isLoggingOut,
                    onClick = {
                        viewModel.logout {
                            showLogoutDialog = false
                            onLoggedOutOrDeleted()
                        }
                    },
                    modifier = Modifier.testTag("confirm_logout_button")
                ) {
                    if (uiState.isLoggingOut) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.action_logout))
                    }
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !uiState.isLoggingOut,
                    onClick = { showLogoutDialog = false }
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (showDeleteAccountDialog) {
        AlertDialog(
            onDismissRequest = { if (!uiState.isDeletingAccount) showDeleteAccountDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.delete_account_title),
                    color = MaterialTheme.colorScheme.error
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(R.string.delete_account_message),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = stringResource(R.string.delete_account_pin_hint),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    FiveDigitPinField(
                        pin = deletePin,
                        onPinChange = {
                            deletePin = it
                            viewModel.clearError()
                        },
                        testTag = "delete_account_pin_input"
                    )
                    uiState.error?.let { err ->
                        Text(
                            text = err.toLocalizedMessage(context),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !uiState.isDeletingAccount && deletePin.length == 5,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    onClick = { viewModel.deleteAccount(deletePin) },
                    modifier = Modifier.testTag("confirm_delete_account_button")
                ) {
                    if (uiState.isDeletingAccount) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        Text(stringResource(R.string.action_delete))
                    }
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !uiState.isDeletingAccount,
                    onClick = { showDeleteAccountDialog = false }
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    uiState.deletionCompletedNotice?.let { notice ->
        showDeleteAccountDialog = false
        AlertDialog(
            onDismissRequest = { viewModel.acknowledgeDeletionAndFinish(onLoggedOutOrDeleted) },
            title = { Text(stringResource(R.string.delete_account_title)) },
            text = {
                Text(
                    text = notice.ifBlank { stringResource(R.string.delete_account_message) },
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.acknowledgeDeletionAndFinish(onLoggedOutOrDeleted) },
                    modifier = Modifier.testTag("deletion_notice_ok_button")
                ) {
                    Text(stringResource(R.string.action_confirm))
                }
            }
        )
    }
}

@Composable
private fun SettingsRowItem(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    isDestructive: Boolean = false,
    onClick: () -> Unit,
    testTag: String
) {
    val tint = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val titleColor = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            tint = tint
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = titleColor
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun openExternalUrl(context: Context, url: String) {
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }.onFailure {
        Toast.makeText(context, context.getString(R.string.err_network), Toast.LENGTH_SHORT).show()
    }
}

private fun openFeedbackEmail(context: Context) {
    val uri = Uri.Builder()
        .scheme("mailto")
        .opaquePart(FEEDBACK_EMAIL)
        .appendQueryParameter("subject", "${context.getString(R.string.app_name)} Feedback")
        .build()
    val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching {
        context.startActivity(intent)
    }.onFailure {
        Toast.makeText(context, context.getString(R.string.err_no_mail_app), Toast.LENGTH_LONG).show()
    }
}
