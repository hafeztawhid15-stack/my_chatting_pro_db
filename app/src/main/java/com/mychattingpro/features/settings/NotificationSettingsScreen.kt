package com.mychattingpro.features.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationSettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit
) {
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var unifiedPushEnabled by remember(prefs.unifiedPushEnabled) { mutableStateOf(prefs.unifiedPushEnabled) }
    var unifiedPushUrl by remember(prefs.unifiedPushEndpointUrl) { mutableStateOf(prefs.unifiedPushEndpointUrl) }

    BackHandler(onBack = onBack)

    LaunchedEffect(uiState.securityNotice) {
        uiState.securityNotice?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearError()
        }
    }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.setNotificationsEnabled(true)
            viewModel.setBackgroundSyncEnabled(true)
        } else {
            Toast.makeText(context, context.getString(R.string.perm_denied), Toast.LENGTH_LONG).show()
            viewModel.setNotificationsEnabled(false)
        }
    }

    fun requestPermissionIfNeededAndEnable(onAllowed: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            onAllowed()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_notifications_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("notif_settings_back")) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item { Spacer(modifier = Modifier.height(4.dp)) }

            item {
                NotificationSectionCard(title = stringResource(R.string.notif_section_general)) {
                    NotificationToggleRow(
                        title = stringResource(R.string.notif_master_label),
                        subtitle = stringResource(R.string.notif_master_desc),
                        checked = prefs.notificationsEnabled,
                        onCheckedChange = { enable ->
                            if (enable) {
                                requestPermissionIfNeededAndEnable { viewModel.setNotificationsEnabled(true) }
                            } else {
                                viewModel.setNotificationsEnabled(false)
                            }
                        },
                        testTag = "notif_toggle_master"
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    NotificationToggleRow(
                        title = stringResource(R.string.notif_messages_label),
                        subtitle = stringResource(R.string.notif_messages_desc),
                        checked = prefs.messageNotificationsEnabled,
                        enabled = prefs.notificationsEnabled,
                        onCheckedChange = viewModel::setMessageNotificationsEnabled,
                        testTag = "notif_toggle_messages"
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    NotificationToggleRow(
                        title = stringResource(R.string.notif_private_messages_label),
                        subtitle = stringResource(R.string.notif_private_messages_desc),
                        checked = prefs.privateChatNotificationsEnabled,
                        enabled = prefs.notificationsEnabled,
                        onCheckedChange = viewModel::setPrivateChatNotificationsEnabled,
                        testTag = "notif_toggle_private"
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    NotificationToggleRow(
                        title = stringResource(R.string.notif_preview_label),
                        subtitle = stringResource(R.string.notif_preview_desc),
                        checked = prefs.showMessagePreview,
                        enabled = prefs.notificationsEnabled,
                        onCheckedChange = viewModel::setShowMessagePreview,
                        testTag = "notif_toggle_preview"
                    )
                }
            }

            item {
                NotificationSectionCard(title = stringResource(R.string.notif_section_sound_alerts)) {
                    NotificationToggleRow(
                        title = stringResource(R.string.notif_sound_label),
                        checked = prefs.messageSoundEnabled,
                        enabled = prefs.notificationsEnabled,
                        onCheckedChange = viewModel::setMessageSoundEnabled,
                        testTag = "notif_toggle_sound"
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    NotificationToggleRow(
                        title = stringResource(R.string.notif_vibration_label),
                        checked = prefs.vibrationEnabled,
                        enabled = prefs.notificationsEnabled,
                        onCheckedChange = viewModel::setVibrationEnabled,
                        testTag = "notif_toggle_vibration"
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    NotificationToggleRow(
                        title = stringResource(R.string.notif_in_app_alerts_label),
                        checked = prefs.inAppAlertsEnabled,
                        enabled = prefs.notificationsEnabled,
                        onCheckedChange = viewModel::setInAppAlertsEnabled,
                        testTag = "notif_toggle_in_app"
                    )
                }
            }

            item {
                NotificationSectionCard(title = stringResource(R.string.notif_section_calls_security)) {
                    NotificationToggleRow(
                        title = stringResource(R.string.notif_calls_label),
                        checked = prefs.callNotificationsEnabled,
                        enabled = prefs.notificationsEnabled,
                        onCheckedChange = viewModel::setCallNotificationsEnabled,
                        testTag = "notif_toggle_calls"
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    NotificationToggleRow(
                        title = stringResource(R.string.notif_missed_calls_label),
                        checked = prefs.missedCallNotificationsEnabled,
                        enabled = prefs.notificationsEnabled,
                        onCheckedChange = viewModel::setMissedCallNotificationsEnabled,
                        testTag = "notif_toggle_missed_calls"
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    NotificationToggleRow(
                        title = stringResource(R.string.notif_call_vibrate_label),
                        checked = prefs.callVibrationEnabled,
                        enabled = prefs.notificationsEnabled,
                        onCheckedChange = viewModel::setCallVibrationEnabled,
                        testTag = "notif_toggle_call_vibration"
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    NotificationToggleRow(
                        title = stringResource(R.string.notif_security_alerts_label),
                        checked = prefs.securityAlertsEnabled,
                        enabled = prefs.notificationsEnabled,
                        onCheckedChange = viewModel::setSecurityAlertsEnabled,
                        testTag = "notif_toggle_security"
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    NotificationToggleRow(
                        title = stringResource(R.string.notif_account_alerts_label),
                        checked = prefs.accountAlertsEnabled,
                        enabled = prefs.notificationsEnabled,
                        onCheckedChange = viewModel::setAccountAlertsEnabled,
                        testTag = "notif_toggle_account"
                    )
                }
            }

            item {
                NotificationSectionCard(title = stringResource(R.string.notif_section_delivery)) {
                    NotificationToggleRow(
                        title = stringResource(R.string.settings_bg_sync),
                        subtitle = stringResource(R.string.settings_bg_sync_desc),
                        checked = prefs.backgroundSyncEnabled,
                        onCheckedChange = { enable ->
                            if (enable) {
                                requestPermissionIfNeededAndEnable { viewModel.setBackgroundSyncEnabled(true) }
                            } else {
                                viewModel.setBackgroundSyncEnabled(false)
                            }
                        },
                        testTag = "notif_toggle_bg_sync"
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.notif_unified_push_label),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = stringResource(R.string.notif_unified_push_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = unifiedPushEnabled,
                                onCheckedChange = { unifiedPushEnabled = it },
                                modifier = Modifier.testTag("notif_toggle_unified_push")
                            )
                        }
                        if (unifiedPushEnabled) {
                            OutlinedTextField(
                                value = unifiedPushUrl,
                                onValueChange = { unifiedPushUrl = it },
                                label = { Text(stringResource(R.string.notif_unified_push_url_label)) },
                                placeholder = { Text("https://ntfy.sh/your-topic") },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("notif_unified_push_url_input")
                            )
                        }
                        Button(
                            onClick = { viewModel.saveUnifiedPushConfig(unifiedPushEnabled, unifiedPushUrl) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("notif_save_push_endpoint")
                        ) {
                            Text(stringResource(R.string.notif_sync_push_btn))
                        }

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            OutlinedButton(
                                onClick = {
                                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    runCatching { context.startActivity(intent) }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(R.string.notif_open_system_settings))
                            }
                        }

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            val pm = context.getSystemService(PowerManager::class.java)
                            val ignoringBattery = pm?.isIgnoringBatteryOptimizations(context.packageName) == true
                            if (!ignoringBattery) {
                                OutlinedButton(
                                    onClick = {
                                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                            data = Uri.parse("package:${context.packageName}")
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        runCatching { context.startActivity(intent) }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(stringResource(R.string.notif_battery_optimization_btn))
                                }
                            }
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(20.dp)) }
        }
    }
}

@Composable
private fun NotificationSectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    ElevatedCard(
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 600.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp)
            )
            content()
        }
    }
}

@Composable
private fun NotificationToggleRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag)
        )
    }
}
