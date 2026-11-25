package com.mychattingpro.features.notifications

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.mychattingpro.core.datastore.AppPreferences
import com.mychattingpro.core.datastore.AppPreferencesDataStore
import com.mychattingpro.core.localization.DateTimeFormatters
import com.mychattingpro.domain.repository.AuthRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(
    authRepository: AuthRepository,
    prefsStore: AppPreferencesDataStore,
    onBack: () -> Unit,
    onOpenActiveDevices: () -> Unit
) {
    val alerts by authRepository.observeSecurityAlerts().collectAsStateWithLifecycle(initialValue = emptyList())
    val prefs by prefsStore.preferencesFlow.collectAsStateWithLifecycle(initialValue = AppPreferences())
    val language = prefs.language
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isRefreshing by remember { mutableStateOf(true) }

    BackHandler(onBack = onBack)

    LaunchedEffect(Unit) {
        authRepository.refreshSecurityAlerts()
        isRefreshing = false
    }

    DisposableEffect(Unit) {
        onDispose {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                runCatching { authRepository.markAllSecurityAlertsRead() }
            }
        }
    }

    val hasUnread = remember(alerts) { alerts.any { !it.isRead } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.notif_center_title)) },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("notifications_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                },
                actions = {
                    if (hasUnread) {
                        TextButton(
                            onClick = { scope.launch { authRepository.markAllSecurityAlertsRead() } },
                            modifier = Modifier.testTag("notifications_mark_all_read")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.DoneAll,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.notif_mark_all_read))
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when {
                isRefreshing && alerts.isEmpty() -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                alerts.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                            modifier = Modifier.size(72.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Outlined.NotificationsNone,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                        }
                        Text(
                            text = stringResource(R.string.notif_center_empty_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = stringResource(R.string.notif_center_empty_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(alerts, key = { it.id }) { alert ->
                            val formatted = remember(alert.id, alert.alertType, alert.deviceName, alert.createdAtMs, language) {
                                SecurityAlertFormatter.format(context, alert, language)
                            }
                            val containerColor = if (formatted.isUrgentLoginAlert && !alert.isRead) {
                                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerLow
                            }
                            val borderStroke = if (formatted.isUrgentLoginAlert) {
                                BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.45f))
                            } else {
                                null
                            }

                            ElevatedCard(
                                shape = RoundedCornerShape(18.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("security_alert_card_${alert.id}")
                            ) {
                                Surface(
                                    color = containerColor,
                                    border = borderStroke,
                                    shape = RoundedCornerShape(18.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            Surface(
                                                shape = RoundedCornerShape(12.dp),
                                                color = if (formatted.isUrgentLoginAlert) {
                                                    MaterialTheme.colorScheme.errorContainer
                                                } else {
                                                    MaterialTheme.colorScheme.primaryContainer
                                                },
                                                modifier = Modifier.size(40.dp)
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Icon(
                                                        imageVector = if (formatted.isUrgentLoginAlert) {
                                                            Icons.Rounded.WarningAmber
                                                        } else {
                                                            Icons.Rounded.Security
                                                        },
                                                        contentDescription = null,
                                                        tint = if (formatted.isUrgentLoginAlert) {
                                                            MaterialTheme.colorScheme.error
                                                        } else {
                                                            MaterialTheme.colorScheme.primary
                                                        }
                                                    )
                                                }
                                            }
                                            Column(modifier = Modifier.weight(1f)) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    Text(
                                                        text = formatted.title,
                                                        style = MaterialTheme.typography.titleSmall,
                                                        fontWeight = FontWeight.Bold,
                                                        color = if (formatted.isUrgentLoginAlert) {
                                                            MaterialTheme.colorScheme.error
                                                        } else {
                                                            MaterialTheme.colorScheme.onSurface
                                                        },
                                                        modifier = Modifier.weight(1f, fill = false)
                                                    )
                                                    if (!alert.isRead) {
                                                        Box(
                                                            modifier = Modifier
                                                                .size(8.dp)
                                                                .clip(CircleShape)
                                                                .background(MaterialTheme.colorScheme.error)
                                                        )
                                                    }
                                                }
                                                Text(
                                                    text = DateTimeFormatters.formatFullDateTime(
                                                        context,
                                                        alert.createdAtMs,
                                                        language
                                                    ),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }

                                        if (!alert.deviceName.isNullOrBlank()) {
                                            Surface(
                                                shape = RoundedCornerShape(10.dp),
                                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Rounded.PhoneAndroid,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(16.dp),
                                                        tint = MaterialTheme.colorScheme.primary
                                                    )
                                                    Text(
                                                        text = alert.deviceName,
                                                        style = MaterialTheme.typography.labelMedium,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                    if (alert.alertType == "new_login") {
                                                        Spacer(modifier = Modifier.weight(1f))
                                                        Text(
                                                            text = if (alert.isSessionActive) {
                                                                stringResource(R.string.device_active_now_badge)
                                                            } else {
                                                                stringResource(R.string.device_signed_out_badge)
                                                            },
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = if (alert.isSessionActive) {
                                                                MaterialTheme.colorScheme.error
                                                            } else {
                                                                MaterialTheme.colorScheme.onSurfaceVariant
                                                            },
                                                            fontWeight = FontWeight.SemiBold
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        Text(
                                            text = formatted.body,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )

                                        if (formatted.isUrgentLoginAlert) {
                                            Button(
                                                onClick = {
                                                    scope.launch { authRepository.markSecurityAlertRead(alert.id) }
                                                    onOpenActiveDevices()
                                                },
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = MaterialTheme.colorScheme.error,
                                                    contentColor = MaterialTheme.colorScheme.onError
                                                ),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(48.dp)
                                                    .testTag("alert_quick_logout_${alert.id}")
                                            ) {
                                                Icon(
                                                    imageVector = Icons.AutoMirrored.Rounded.Logout,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = stringResource(R.string.security_quick_logout_btn),
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        } else {
                                            OutlinedButton(
                                                onClick = {
                                                    scope.launch { authRepository.markSecurityAlertRead(alert.id) }
                                                    onOpenActiveDevices()
                                                },
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(48.dp)
                                                    .testTag("alert_check_devices_${alert.id}")
                                            ) {
                                                Text(stringResource(R.string.settings_active_devices))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
