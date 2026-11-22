package com.mychattingpro.features.calls

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.CallMade
import androidx.compose.material.icons.automirrored.rounded.CallMissed
import androidx.compose.material.icons.automirrored.rounded.CallReceived
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.R
import com.mychattingpro.core.common.AppError
import com.mychattingpro.core.localization.DateTimeFormatters
import com.mychattingpro.core.network.NetworkModule
import com.mychattingpro.data.repository.AppException
import com.mychattingpro.domain.models.CallHistoryFilter
import com.mychattingpro.domain.models.CallHistoryItem
import com.mychattingpro.domain.repository.CallRepository
import com.mychattingpro.domain.repository.ChatRepository
import com.mychattingpro.features.common.PresenceBadge
import com.mychattingpro.features.common.UserAvatar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class CallHistoryUiState(
    val selectedFilter: CallHistoryFilter = CallHistoryFilter.ALL,
    val calls: List<CallHistoryItem> = emptyList(),
    val peerOnlineMap: Map<String, Boolean> = emptyMap(),
    val isRefreshing: Boolean = false,
    val error: AppError? = null
)

class CallHistoryViewModel(
    private val callRepository: CallRepository,
    private val chatRepository: ChatRepository? = null
) : ViewModel() {

    private val filterFlow = MutableStateFlow(CallHistoryFilter.ALL)
    private val refreshingFlow = MutableStateFlow(false)
    private val errorFlow = MutableStateFlow<AppError?>(null)

    val uiState: StateFlow<CallHistoryUiState> = combine(
        filterFlow,
        callRepository.observeCallHistory(),
        chatRepository?.observeConversations() ?: flowOf(emptyList()),
        refreshingFlow,
        errorFlow
    ) { filter, allCalls, conversations, refreshing, err ->
        val filtered = when (filter) {
            CallHistoryFilter.ALL -> allCalls
            CallHistoryFilter.INCOMING -> allCalls.filter { it.direction == "incoming" }
            CallHistoryFilter.OUTGOING -> allCalls.filter { it.direction == "outgoing" }
            CallHistoryFilter.MISSED -> allCalls.filter { it.status == "missed" }
            CallHistoryFilter.REJECTED -> allCalls.filter { it.status == "rejected" }
        }
        val onlineMap = conversations.associate { it.peerUserId to it.peerIsOnline }
        CallHistoryUiState(
            selectedFilter = filter,
            calls = filtered,
            peerOnlineMap = onlineMap,
            isRefreshing = refreshing,
            error = err
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CallHistoryUiState())

    init {
        refresh()
    }

    fun selectFilter(filter: CallHistoryFilter) {
        filterFlow.value = filter
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            refreshingFlow.value = true
            errorFlow.value = null
            chatRepository?.let { launch { it.refreshConversations() } }
            callRepository.refreshCallHistory(CallHistoryFilter.ALL)
                .onFailure { err ->
                    errorFlow.value = (err as? AppException)?.appError ?: AppError.ServerError()
                }
            refreshingFlow.value = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallHistoryScreen(
    viewModel: CallHistoryViewModel,
    networkModule: NetworkModule,
    onBack: () -> Unit,
    onStartCallback: (CallHistoryItem, Boolean) -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.refresh()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.call_history_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("call_history_back_button")) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }, modifier = Modifier.testTag("call_history_refresh_button")) {
                        Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.action_retry))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (state.isRefreshing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val filters = listOf(
                    CallHistoryFilter.ALL to context.getString(R.string.call_filter_all),
                    CallHistoryFilter.INCOMING to context.getString(R.string.call_filter_incoming),
                    CallHistoryFilter.OUTGOING to context.getString(R.string.call_filter_outgoing),
                    CallHistoryFilter.MISSED to context.getString(R.string.call_filter_missed),
                    CallHistoryFilter.REJECTED to context.getString(R.string.call_filter_rejected)
                )
                items(filters) { (filter, label) ->
                    FilterChip(
                        selected = state.selectedFilter == filter,
                        onClick = { viewModel.selectFilter(filter) },
                        label = { Text(label) },
                        modifier = Modifier.testTag("call_filter_${filter.apiValue}")
                    )
                }
            }

            state.error?.let { err ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = err.toLocalizedMessage(context),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            if (state.calls.isEmpty() && !state.isRefreshing) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.History,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.call_history_empty_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.call_history_empty_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(state.calls, key = { it.callId }) { item ->
                        CallHistoryRow(
                            item = item,
                            peerIsOnline = state.peerOnlineMap[item.peerUserId],
                            networkModule = networkModule,
                            onAudioCall = { onStartCallback(item, false) },
                            onVideoCall = { onStartCallback(item, true) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CallHistoryRow(
    item: CallHistoryItem,
    peerIsOnline: Boolean?,
    networkModule: NetworkModule,
    onAudioCall: () -> Unit,
    onVideoCall: () -> Unit
) {
    val isMissedOrRejected = item.status == "missed" || item.status == "rejected"
    val directionIcon = when {
        item.status == "missed" -> Icons.AutoMirrored.Rounded.CallMissed
        item.direction == "incoming" -> Icons.AutoMirrored.Rounded.CallReceived
        else -> Icons.AutoMirrored.Rounded.CallMade
    }
    val tint = if (isMissedOrRejected) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary

    val statusText = when (item.status) {
        "missed" -> stringResource(R.string.call_filter_missed)
        "rejected" -> stringResource(R.string.call_filter_rejected)
        else -> if (item.durationSeconds > 0) {
            DateTimeFormatters.formatDuration(item.durationSeconds.toLong())
        } else {
            stringResource(R.string.call_ended)
        }
    }

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("call_history_row_${item.callId}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            UserAvatar(
                displayName = item.peerDisplayName,
                avatarMediaId = item.peerAvatarMediaId,
                networkModule = networkModule,
                size = 48.dp,
                isOnline = peerIsOnline == true,
                showOfflineDot = peerIsOnline != null
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = item.peerDisplayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (peerIsOnline != null) {
                        Spacer(modifier = Modifier.width(6.dp))
                        PresenceBadge(isOnline = peerIsOnline)
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = directionIcon,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    val context = LocalContext.current
                    Text(
                        text = "$statusText • ${DateTimeFormatters.formatFullDateTime(context, item.startedAtMs, com.mychattingpro.core.localization.AppLanguage.ENGLISH)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isMissedOrRejected) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            IconButton(
                onClick = onAudioCall,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("callback_audio_${item.callId}")
            ) {
                Icon(
                    imageVector = Icons.Rounded.Call,
                    contentDescription = stringResource(R.string.call_audio),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            IconButton(
                onClick = onVideoCall,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("callback_video_${item.callId}")
            ) {
                Icon(
                    imageVector = Icons.Rounded.Videocam,
                    contentDescription = stringResource(R.string.call_video),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
