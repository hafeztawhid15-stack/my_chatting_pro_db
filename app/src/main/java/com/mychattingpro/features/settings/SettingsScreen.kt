package com.mychattingpro.features.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.outlined.AutoDelete
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockReset
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Policy
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.SystemUpdate
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.BuildConfig
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
    onOpenNotificationSettings: () -> Unit,
    onOpenBlockedUsers: () -> Unit,
    onOpenActiveDevices: () -> Unit,
    onOpenChangeLoginPin: () -> Unit,
    onOpenRecoveryCode: () -> Unit,
    onOpenForgotLoginPin: () -> Unit,
    onLoggedOutOrDeleted: () -> Unit
) {
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }

    var showLanguageDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showVersionDialog by remember { mutableStateOf(false) }
    var showLogoutDialog by remember { mutableStateOf(false) }
    var showLogoutAllDialog by remember { mutableStateOf(false) }
    var showDeleteAccountDialog by remember { mutableStateOf(false) }
    var deletePin by remember { mutableStateOf("") }
    var deleteConfirmText by remember { mutableStateOf("") }
    var showPrivatePinDialog by remember { mutableStateOf(false) }
    var showAutoDeleteInfoDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        interstitialAdManager.preload(resetRetry = true)
        viewModel.loadPrivatePinStatus()
    }

    LaunchedEffect(uiState.deletionCompletedNotice) {
        if (uiState.deletionCompletedNotice != null) {
            showDeleteAccountDialog = false
        }
    }

    LaunchedEffect(uiState.securityNotice) {
        uiState.securityNotice?.let { notice ->
            Toast.makeText(context, notice, Toast.LENGTH_SHORT).show()
            viewModel.clearError()
        }
    }

    LaunchedEffect(uiState.privatePinNoticeResId) {
        val noticeId = uiState.privatePinNoticeResId ?: return@LaunchedEffect
        Toast.makeText(context, context.getString(noticeId), Toast.LENGTH_SHORT).show()
        showPrivatePinDialog = false
        viewModel.clearPrivatePinFeedback()
    }

    fun withSettingsAd(action: () -> Unit) {
        interstitialAdManager.showThen(activity, action)
    }

    val appVersionName = BuildConfig.VERSION_NAME
    val appVersionCode = BuildConfig.VERSION_CODE

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
                // Profile Card -> opens ProfileScreen in read-only view mode (Edit button is only top-left inside ProfileScreen)
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
                                    ?: stringResource(R.string.profile_title),
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
                            contentDescription = stringResource(R.string.profile_title),
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
                // Appearance, Language & Notifications
                ElevatedCard(
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 600.dp)
                ) {
                    Column {
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
                        SettingsRowItem(
                            icon = Icons.Outlined.Notifications,
                            title = stringResource(R.string.settings_notifications_title),
                            subtitle = stringResource(R.string.settings_notifications_desc),
                            onClick = { withSettingsAd(onOpenNotificationSettings) },
                            testTag = "settings_notifications"
                        )
                    }
                }
            }

            item {
                // Account Security Section
                SettingsSectionHeader(title = stringResource(R.string.settings_section_security))
                ElevatedCard(
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 600.dp)
                ) {
                    Column {
                        SettingsRowItem(
                            icon = Icons.Outlined.Password,
                            title = stringResource(R.string.change_login_pin_title),
                            onClick = { withSettingsAd(onOpenChangeLoginPin) },
                            testTag = "settings_change_login_pin"
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        SettingsRowItem(
                            icon = Icons.Outlined.Key,
                            title = stringResource(R.string.recovery_code_title),
                            subtitle = if (prefs.recoveryCodeConfigured) {
                                stringResource(R.string.recovery_code_status_active)
                            } else {
                                stringResource(R.string.recovery_code_status_missing)
                            },
                            onClick = { withSettingsAd(onOpenRecoveryCode) },
                            testTag = "settings_recovery_code"
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        SettingsRowItem(
                            icon = Icons.Outlined.LockReset,
                            title = stringResource(R.string.forgot_login_pin_title),
                            onClick = { withSettingsAd(onOpenForgotLoginPin) },
                            testTag = "settings_forgot_login_pin"
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        SettingsRowItem(
                            icon = Icons.Outlined.Lock,
                            title = stringResource(R.string.settings_private_pin),
                            subtitle = if (uiState.hasPrivatePin == false) {
                                stringResource(R.string.settings_private_pin_not_set_desc)
                            } else {
                                stringResource(R.string.settings_private_pin_desc)
                            },
                            onClick = {
                                withSettingsAd {
                                    viewModel.clearPrivatePinFeedback()
                                    viewModel.loadPrivatePinStatus()
                                    showPrivatePinDialog = true
                                }
                            },
                            testTag = "settings_private_pin"
                        )
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
                            icon = Icons.AutoMirrored.Rounded.Logout,
                            title = stringResource(R.string.logout_all_devices_title),
                            subtitle = stringResource(R.string.logout_all_devices_desc),
                            onClick = { withSettingsAd { showLogoutAllDialog = true } },
                            testTag = "settings_logout_all_devices"
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        SettingsRowItem(
                            icon = Icons.Outlined.Block,
                            title = stringResource(R.string.settings_blocked_users),
                            onClick = { withSettingsAd(onOpenBlockedUsers) },
                            testTag = "settings_blocked_users"
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        SettingsRowItem(
                            icon = Icons.Outlined.AutoDelete,
                            title = stringResource(R.string.settings_auto_delete_title),
                            subtitle = stringResource(R.string.settings_auto_delete_subtitle),
                            onClick = { showAutoDeleteInfoDialog = true },
                            testTag = "settings_auto_delete"
                        )
                    }
                }
            }

            item {
                // App Info, Updates & Support
                SettingsSectionHeader(title = stringResource(R.string.settings_section_app_info))
                ElevatedCard(
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 600.dp)
                ) {
                    Column {
                        SettingsRowItem(
                            icon = Icons.Outlined.SystemUpdate,
                            title = stringResource(R.string.check_for_updates),
                            subtitle = if (uiState.isCheckingUpdate) {
                                stringResource(R.string.checking_updates)
                            } else {
                                "v$appVersionName ($appVersionCode)"
                            },
                            onClick = { withSettingsAd { viewModel.checkForUpdates() } },
                            testTag = "settings_check_updates"
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        SettingsRowItem(
                            icon = Icons.Outlined.StarOutline,
                            title = stringResource(R.string.rate_this_app),
                            onClick = { withSettingsAd { openPlayStoreListing(context) } },
                            testTag = "settings_rate_app"
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        SettingsRowItem(
                            icon = Icons.Outlined.Share,
                            title = stringResource(R.string.share_app),
                            onClick = { withSettingsAd { shareApp(context) } },
                            testTag = "settings_share_app"
                        )
                    }
                }
            }

            item {
                // External Links, Feedback, Privacy Policy, Version (Links strictly preserved)
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
                            subtitle = "v$appVersionName ($appVersionCode)",
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
                                    deleteConfirmText = ""
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
                        text = stringResource(R.string.version_name_label, appVersionName),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = stringResource(R.string.version_code_label, appVersionCode.toLong()),
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

    if (showAutoDeleteInfoDialog) {
        AlertDialog(
            onDismissRequest = { showAutoDeleteInfoDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Outlined.AutoDelete,
                    contentDescription = stringResource(R.string.settings_auto_delete_title),
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = { Text(stringResource(R.string.settings_auto_delete_title)) },
            text = {
                Text(
                    text = stringResource(R.string.settings_auto_delete_info_body),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { showAutoDeleteInfoDialog = false },
                    modifier = Modifier.testTag("settings_auto_delete_dialog_confirm")
                ) {
                    Text(stringResource(R.string.action_confirm))
                }
            }
        )
    }

    if (uiState.appUpdateInfo != null || uiState.updateCheckError != null) {
        val info = uiState.appUpdateInfo
        AlertDialog(
            onDismissRequest = viewModel::dismissUpdateDialog,
            title = {
                Text(
                    if (info?.isUpdateAvailable == true) {
                        stringResource(R.string.update_available_title)
                    } else {
                        stringResource(R.string.check_for_updates)
                    }
                )
            },
            text = {
                Text(
                    when {
                        uiState.updateCheckError != null -> uiState.updateCheckError!!
                        info?.isUpdateAvailable == true -> stringResource(
                            R.string.update_available_message,
                            info.storeVersionName ?: info.currentVersionName
                        )
                        else -> stringResource(
                            R.string.update_up_to_date,
                            info?.currentVersionName ?: appVersionName
                        )
                    }
                )
            },
            confirmButton = {
                if (info?.isUpdateAvailable == true) {
                    TextButton(
                        onClick = {
                            viewModel.dismissUpdateDialog()
                            openPlayStoreListing(context)
                        }
                    ) {
                        Text(stringResource(R.string.update_download_btn))
                    }
                } else {
                    TextButton(onClick = viewModel::dismissUpdateDialog) {
                        Text(stringResource(R.string.action_confirm))
                    }
                }
            },
            dismissButton = {
                if (info?.isUpdateAvailable == true) {
                    TextButton(onClick = viewModel::dismissUpdateDialog) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            }
        )
    }

    if (showLogoutAllDialog) {
        AlertDialog(
            onDismissRequest = { if (!uiState.isLoggingOutAllDevices) showLogoutAllDialog = false },
            title = { Text(stringResource(R.string.logout_all_devices_title)) },
            text = { Text(stringResource(R.string.logout_all_devices_desc)) },
            confirmButton = {
                TextButton(
                    enabled = !uiState.isLoggingOutAllDevices,
                    onClick = {
                        showLogoutAllDialog = false
                        viewModel.logoutAllDevices(keepCurrent = true)
                    },
                    modifier = Modifier.testTag("settings_confirm_logout_other_devices")
                ) {
                    Text(stringResource(R.string.logout_other_devices_btn))
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        enabled = !uiState.isLoggingOutAllDevices,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        onClick = {
                            showLogoutAllDialog = false
                            viewModel.logoutAllDevices(keepCurrent = false, onAllLoggedOut = onLoggedOutOrDeleted)
                        },
                        modifier = Modifier.testTag("settings_confirm_logout_all_devices")
                    ) {
                        Text(stringResource(R.string.logout_all_devices_including_this))
                    }
                    TextButton(onClick = { showLogoutAllDialog = false }) {
                        Text(stringResource(R.string.action_cancel))
                    }
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
        val canSubmitDelete = !uiState.isDeletingAccount &&
            deletePin.length == 5 &&
            deleteConfirmText.trim().uppercase() == "DELETE"

        AlertDialog(
            onDismissRequest = { if (!uiState.isDeletingAccount) showDeleteAccountDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.delete_account_title),
                    color = MaterialTheme.colorScheme.error
                )
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
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
                    OutlinedTextField(
                        value = deleteConfirmText,
                        onValueChange = {
                            deleteConfirmText = it
                            viewModel.clearError()
                        },
                        label = { Text(stringResource(R.string.delete_account_confirm_word_hint)) },
                        placeholder = { Text("DELETE") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("delete_account_confirm_word_input")
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
                    enabled = canSubmitDelete,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    onClick = { viewModel.deleteAccount(deletePin, deleteConfirmText) },
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

    if (showPrivatePinDialog) {
        PrivatePinSettingsDialog(
            hasPrivatePin = uiState.hasPrivatePin,
            isBusy = uiState.isPrivatePinBusy,
            errorText = uiState.privatePinError?.toLocalizedMessage(context),
            onDismiss = {
                if (!uiState.isPrivatePinBusy) {
                    viewModel.clearPrivatePinFeedback()
                    showPrivatePinDialog = false
                }
            },
            onClearError = viewModel::clearPrivatePinFeedback,
            onSetup = viewModel::setupPrivatePinFromSettings,
            onChange = viewModel::changePrivatePin,
            onReset = viewModel::resetPrivatePinWithLoginPin
        )
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 600.dp)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    )
}

@Composable
private fun PrivatePinSettingsDialog(
    hasPrivatePin: Boolean?,
    isBusy: Boolean,
    errorText: String?,
    onDismiss: () -> Unit,
    onClearError: () -> Unit,
    onSetup: (String, String) -> Unit,
    onChange: (String, String, String) -> Unit,
    onReset: (String, String, String) -> Unit
) {
    var resetMode by remember(hasPrivatePin) { mutableStateOf(false) }
    var oldOrLoginPin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }

    val isSetupMode = hasPrivatePin == false

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = when {
                    isSetupMode -> stringResource(R.string.private_pin_setup_title)
                    resetMode -> stringResource(R.string.private_pin_reset_title)
                    else -> stringResource(R.string.private_pin_change_title)
                },
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (isSetupMode) {
                    Text(
                        text = stringResource(R.string.private_pin_setup_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (resetMode) {
                    Text(
                        text = stringResource(R.string.private_pin_reset_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(R.string.private_pin_enter_account_login_pin),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    FiveDigitPinField(
                        pin = oldOrLoginPin,
                        onPinChange = {
                            oldOrLoginPin = it
                            onClearError()
                        },
                        testTag = "settings_private_pin_login_input"
                    )
                } else {
                    Text(
                        text = stringResource(R.string.private_pin_old_label),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    FiveDigitPinField(
                        pin = oldOrLoginPin,
                        onPinChange = {
                            oldOrLoginPin = it
                            onClearError()
                        },
                        testTag = "settings_private_pin_old_input"
                    )
                }

                Text(
                    text = stringResource(R.string.private_pin_new_label),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                FiveDigitPinField(
                    pin = newPin,
                    onPinChange = {
                        newPin = it
                        onClearError()
                    },
                    testTag = "settings_private_pin_new_input"
                )

                Text(
                    text = stringResource(R.string.private_pin_confirm_label),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                FiveDigitPinField(
                    pin = confirmPin,
                    onPinChange = {
                        confirmPin = it
                        onClearError()
                    },
                    testTag = "settings_private_pin_confirm_input"
                )

                if (!errorText.isNullOrBlank()) {
                    Text(
                        text = errorText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("settings_private_pin_error")
                    )
                }

                if (!isSetupMode) {
                    TextButton(
                        enabled = !isBusy,
                        onClick = {
                            resetMode = !resetMode
                            oldOrLoginPin = ""
                            newPin = ""
                            confirmPin = ""
                            onClearError()
                        },
                        modifier = Modifier.testTag("settings_private_pin_toggle_reset")
                    ) {
                        Text(
                            text = if (resetMode) {
                                stringResource(R.string.private_pin_change_title)
                            } else {
                                stringResource(R.string.private_pin_forgot_btn)
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !isBusy,
                onClick = {
                    when {
                        isSetupMode -> onSetup(newPin, confirmPin)
                        resetMode -> onReset(oldOrLoginPin, newPin, confirmPin)
                        else -> onChange(oldOrLoginPin, newPin, confirmPin)
                    }
                },
                modifier = Modifier.testTag("settings_private_pin_submit")
            ) {
                if (isBusy) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.action_save))
                }
            }
        },
        dismissButton = {
            TextButton(enabled = !isBusy, onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
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
            .heightIn(min = 48.dp)
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
        Toast.makeText(context, context.getString(R.string.err_no_browser), Toast.LENGTH_SHORT).show()
    }
}

private fun openPlayStoreListing(context: Context) {
    val pkg = BuildConfig.APPLICATION_ID
    val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(marketIntent)
    } catch (_: ActivityNotFoundException) {
        openExternalUrl(context, "https://play.google.com/store/apps/details?id=$pkg")
    } catch (_: Throwable) {
        openExternalUrl(context, "https://play.google.com/store/apps/details?id=$pkg")
    }
}

private fun shareApp(context: Context) {
    val appUrl = "https://play.google.com/store/apps/details?id=${BuildConfig.APPLICATION_ID}"
    val shareText = context.getString(R.string.share_app_message, appUrl)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.app_name))
        putExtra(Intent.EXTRA_TEXT, shareText)
    }
    runCatching {
        val chooser = Intent.createChooser(intent, context.getString(R.string.share_app)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
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
