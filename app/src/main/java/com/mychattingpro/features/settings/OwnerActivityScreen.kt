package com.mychattingpro.features.settings

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.mychattingpro.core.common.CountryCatalog
import com.mychattingpro.core.localization.DateTimeFormatters
import com.mychattingpro.core.theme.OnlineGreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OwnerActivityScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val language by viewModel.language.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var ownerKeyInput by remember { mutableStateOf("") }

    BackHandler(onBack = onBack)

    LaunchedEffect(Unit) {
        viewModel.initOwnerMonitor()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_owner_monitor)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("owner_back_button")) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                },
                actions = {
                    if (uiState.ownerActivity != null) {
                        IconButton(
                            onClick = { viewModel.refreshOwnerActivity() },
                            enabled = !uiState.isLoadingOwnerActivity,
                            modifier = Modifier.testTag("owner_refresh_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = stringResource(R.string.action_retry)
                            )
                        }
                        TextButton(
                            onClick = { viewModel.lockOwnerMonitor() },
                            modifier = Modifier.testTag("owner_lock_button")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Lock,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.owner_lock_btn))
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
            val snapshot = uiState.ownerActivity
            if (snapshot == null && !uiState.hasSavedOwnerKey) {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(68.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.Shield,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(34.dp)
                            )
                        }
                    }
                    Text(
                        text = stringResource(R.string.settings_owner_name),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.settings_owner_monitor_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = ownerKeyInput,
                        onValueChange = {
                            ownerKeyInput = it
                            viewModel.clearError()
                        },
                        label = { Text(stringResource(R.string.owner_key_label)) },
                        placeholder = { Text(stringResource(R.string.owner_key_hint)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("owner_key_input")
                    )
                    uiState.ownerError?.let { err ->
                        Text(
                            text = err.toLocalizedMessage(context),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Button(
                        onClick = { viewModel.unlockAndLoadOwnerActivity(ownerKeyInput) },
                        enabled = !uiState.isLoadingOwnerActivity && ownerKeyInput.isNotBlank(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("owner_unlock_button")
                    ) {
                        if (uiState.isLoadingOwnerActivity) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Text(stringResource(R.string.owner_unlock_btn))
                        }
                    }
                }
            } else if (uiState.isLoadingOwnerActivity && snapshot == null) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (snapshot != null) {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OwnerStatCard(
                                label = stringResource(R.string.owner_stat_total_users),
                                value = snapshot.summary.totalRegisteredUsers.toString(),
                                modifier = Modifier.weight(1f)
                            )
                            OwnerStatCard(
                                label = stringResource(R.string.owner_stat_online_now),
                                value = snapshot.summary.currentlyOnlineUsers.toString(),
                                highlight = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OwnerStatCard(
                                label = stringResource(R.string.owner_stat_active_24h),
                                value = snapshot.summary.activeLast24hUsers.toString(),
                                modifier = Modifier.weight(1f)
                            )
                            OwnerStatCard(
                                label = stringResource(R.string.owner_stat_sessions),
                                value = snapshot.summary.activeSessionsCount.toString(),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = uiState.ownerSearchQuery,
                                onValueChange = { viewModel.updateOwnerSearchQuery(it) },
                                placeholder = { Text(stringResource(R.string.owner_search_hint)) },
                                singleLine = true,
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Rounded.Search,
                                        contentDescription = null
                                    )
                                },
                                trailingIcon = {
                                    Row {
                                        if (uiState.ownerSearchQuery.isNotEmpty()) {
                                            IconButton(
                                                onClick = {
                                                    viewModel.updateOwnerSearchQuery("")
                                                    viewModel.searchOwnerUsers()
                                                },
                                                modifier = Modifier.testTag("owner_search_clear_button")
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.Close,
                                                    contentDescription = stringResource(R.string.action_cancel)
                                                )
                                            }
                                        }
                                        IconButton(
                                            onClick = { viewModel.searchOwnerUsers() },
                                            modifier = Modifier.testTag("owner_search_button")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Search,
                                                contentDescription = stringResource(R.string.owner_search_hint)
                                            )
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("owner_search_input")
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.owner_recent_users),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = stringResource(
                                        R.string.owner_showing_count,
                                        snapshot.users.size,
                                        snapshot.totalCount
                                    ),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    items(snapshot.users, key = { it.userId }) { user ->
                        val country = CountryCatalog.findByIso(user.countryIso2)
                        ElevatedCard(
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("owner_user_card_${user.userId}")
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (user.isOnline) OnlineGreen
                                            else MaterialTheme.colorScheme.outlineVariant
                                        )
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${country.flagEmoji} ${user.displayName}",
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "${user.maskedPhone} • ${
                                            if (user.isOnline) {
                                                stringResource(R.string.status_online)
                                            } else {
                                                val ts = user.lastSeenMs ?: user.createdAtMs
                                                stringResource(
                                                    R.string.status_last_seen,
                                                    DateTimeFormatters.formatChatListTime(context, ts, language)
                                                )
                                            }
                                        }",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (user.isOnline) OnlineGreen else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Outlined.PhoneAndroid,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "${user.latestDeviceName} (v${user.latestAppVersion}) • ${
                                                stringResource(
                                                    R.string.owner_joined_label,
                                                    DateTimeFormatters.formatChatListTime(
                                                        context,
                                                        user.createdAtMs,
                                                        language
                                                    )
                                                )
                                            }",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (snapshot.hasMore) {
                        item {
                            OutlinedButton(
                                onClick = { viewModel.loadMoreOwnerUsers() },
                                enabled = !uiState.isLoadingMoreOwnerUsers,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("owner_load_more_button")
                            ) {
                                if (uiState.isLoadingMoreOwnerUsers) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Text(stringResource(R.string.owner_load_more))
                                }
                            }
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = uiState.ownerError?.toLocalizedMessage(context)
                            ?: stringResource(R.string.err_server),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Button(
                        onClick = { viewModel.refreshOwnerActivity() },
                        modifier = Modifier.testTag("owner_retry_button")
                    ) {
                        Text(stringResource(R.string.action_retry))
                    }
                    TextButton(onClick = { viewModel.lockOwnerMonitor() }) {
                        Text(stringResource(R.string.owner_lock_btn))
                    }
                }
            }
        }
    }
}

@Composable
private fun OwnerStatCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    highlight: Boolean = false
) {
    ElevatedCard(
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
                color = if (highlight) OnlineGreen else MaterialTheme.colorScheme.primary
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
