package com.mychattingpro.features.calls

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
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
import com.mychattingpro.core.localization.AppLanguage
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
    val allCallCount: Int = 0,
    val peerOnlineMap: Map<String, Boolean> = emptyMap(),
    val selectedCallIds: Set<String> = emptySet(),
    val isRefreshing: Boolean = false,
    val isDeleting: Boolean = false,
    val error: AppError? = null,
    val infoNoticeResId: Int? = null
)

class CallHistoryViewModel(
    private val callRepository: CallRepository,
    private val chatRepository: ChatRepository? = null
) : ViewModel() {

    private val filterFlow = MutableStateFlow(CallHistoryFilter.ALL)
    private val selectedCallIdsFlow = MutableStateFlow<Set<String>>(emptySet())
    private val refreshingFlow = MutableStateFlow(false)
    private val deletingFlow = MutableStateFlow(false)
    private val errorFlow = MutableStateFlow<AppError?>(null)
    private val infoNoticeResIdFlow = MutableStateFlow<Int?>(null)

    private val metaFlow = combine(
        selectedCallIdsFlow,
        refreshingFlow,
        deletingFlow,
        errorFlow,
        infoNoticeResIdFlow
    ) { selectedIds, refreshing, deleting, err, noticeResId ->
        MetaState(
            selectedCallIds = selectedIds,
            isRefreshing = refreshing,
            isDeleting = deleting,
            error = err,
            infoNoticeResId = noticeResId
        )
    }

    val uiState: StateFlow<CallHistoryUiState> = combine(
        filterFlow,
        callRepository.observeCallHistory(),
        chatRepository?.observeConversations() ?: flowOf(emptyList()),
        metaFlow
    ) { filter, allCalls, conversations, meta ->
        val filtered = when (filter) {
            CallHistoryFilter.ALL -> allCalls
            CallHistoryFilter.INCOMING -> allCalls.filter { it.direction == "incoming" }
            CallHistoryFilter.OUTGOING -> allCalls.filter { it.direction == "outgoing" }
            CallHistoryFilter.MISSED -> allCalls.filter { it.status == "missed" }
            CallHistoryFilter.REJECTED -> allCalls.filter { it.status == "rejected" }
        }
        val validIds = filtered.map { it.callId }.toSet()
        val activeSelection = meta.selectedCallIds.intersect(validIds)
        val onlineMap = conversations.associate { it.peerUserId to it.peerIsOnline }
        CallHistoryUiState(
            selectedFilter = filter,
            calls = filtered,
            allCallCount = allCalls.size,
            peerOnlineMap = onlineMap,
            selectedCallIds = activeSelection,
            isRefreshing = meta.isRefreshing,
            isDeleting = meta.isDeleting,
            error = meta.error,
            infoNoticeResId = meta.infoNoticeResId
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CallHistoryUiState())

    init {
        refresh()
    }

    fun selectFilter(filter: CallHistoryFilter) {
        filterFlow.value = filter
        selectedCallIdsFlow.value = emptySet()
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

    fun toggleSelection(callId: String) {
        val current = selectedCallIdsFlow.value
        selectedCallIdsFlow.value = if (callId in current) current - callId else current + callId
    }

    fun selectAllVisible() {
        val visibleIds = uiState.value.calls.map { it.callId }.toSet()
        selectedCallIdsFlow.value = if (selectedCallIdsFlow.value.containsAll(visibleIds) && visibleIds.isNotEmpty()) {
            emptySet()
        } else {
            visibleIds
        }
    }

    fun clearSelection() {
        selectedCallIdsFlow.value = emptySet()
    }

    fun deleteCall(callId: String) {
        viewModelScope.launch {
            deletingFlow.value = true
            errorFlow.value = null
            callRepository.deleteCallRecord(callId)
                .onSuccess {
                    selectedCallIdsFlow.value = selectedCallIdsFlow.value - callId
                    infoNoticeResIdFlow.value = R.string.call_history_deleted_notice
                }
                .onFailure { err ->
                    errorFlow.value = (err as? AppException)?.appError ?: AppError.ServerError()
                }
            deletingFlow.value = false
        }
    }

    fun deleteSelectedCalls() {
        val ids = selectedCallIdsFlow.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            deletingFlow.value = true
            errorFlow.value = null
            callRepository.deleteCallRecords(ids)
                .onSuccess {
                    selectedCallIdsFlow.value = emptySet()
                    infoNoticeResIdFlow.value = R.string.call_history_deleted_notice
                }
                .onFailure { err ->
                    errorFlow.value = (err as? AppException)?.appError ?: AppError.ServerError()
                }
            deletingFlow.value = false
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            deletingFlow.value = true
            errorFlow.value = null
            callRepository.clearAllCallHistory()
                .onSuccess {
                    selectedCallIdsFlow.value = emptySet()
                    infoNoticeResIdFlow.value = R.string.call_history_cleared_notice
                }
                .onFailure { err ->
                    errorFlow.value = (err as? AppException)?.appError ?: AppError.ServerError()
                }
            deletingFlow.value = false
        }
    }

    fun clearNotice() {
        infoNoticeResIdFlow.value = null
    }

    private data class MetaState(
        val selectedCallIds: Set<String>,
        val isRefreshing: Boolean,
        val isDeleting: Boolean,
        val error: AppError?,
        val infoNoticeResId: Int?
    )
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
    val inSelectionMode = state.selectedCallIds.isNotEmpty()

    var pendingDeleteSingle by remember { mutableStateOf<CallHistoryItem?>(null) }
    var showConfirmDeleteBatch by remember { mutableStateOf(false) }
    var showConfirmClearAll by remember { mutableStateOf(false) }

    BackHandler(enabled = inSelectionMode) {
        viewModel.clearSelection()
    }

    LaunchedEffect(Unit) {
        viewModel.refresh()
    }

    LaunchedEffect(state.infoNoticeResId) {
        val resId = state.infoNoticeResId ?: return@LaunchedEffect
        Toast.makeText(context, context.getString(resId), Toast.LENGTH_SHORT).show()
        viewModel.clearNotice()
    }

    Scaffold(
        topBar = {
            if (inSelectionMode) {
                TopAppBar(
                    title = {
                        Text(
                            text = stringResource(R.string.call_selected_count, state.selectedCallIds.size),
                            fontWeight = FontWeight.Bold
                        )
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = { viewModel.clearSelection() },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("call_history_close_selection_button")
                        ) {
                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_cancel))
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { viewModel.selectAllVisible() },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("call_history_select_all_button")
                        ) {
                            Icon(Icons.Rounded.SelectAll, contentDescription = stringResource(R.string.call_select_all))
                        }
                        IconButton(
                            onClick = { showConfirmDeleteBatch = true },
                            enabled = !state.isDeleting,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("call_history_delete_selected_button")
                        ) {
                            Icon(
                                Icons.Rounded.Delete,
                                contentDescription = stringResource(R.string.call_delete_selected),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                )
            } else {
                TopAppBar(
                    title = { Text(stringResource(R.string.call_history_title), fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("call_history_back_button")
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                    actions = {
                        if (state.allCallCount > 0 || state.calls.isNotEmpty()) {
                            IconButton(
                                onClick = { showConfirmClearAll = true },
                                enabled = !state.isDeleting,
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("call_history_clear_all_button")
                            ) {
                                Icon(
                                    Icons.Rounded.DeleteSweep,
                                    contentDescription = stringResource(R.string.call_clear_all),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        IconButton(
                            onClick = { viewModel.refresh() },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("call_history_refresh_button")
                        ) {
                            Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.action_retry))
                        }
                    }
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (state.isRefreshing || state.isDeleting) {
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
                        val isSelected = item.callId in state.selectedCallIds
                        CallHistoryRow(
                            item = item,
                            peerIsOnline = state.peerOnlineMap[item.peerUserId],
                            networkModule = networkModule,
                            inSelectionMode = inSelectionMode,
                            isSelected = isSelected,
                            onToggleSelect = { viewModel.toggleSelection(item.callId) },
                            onDeleteSingle = { pendingDeleteSingle = item },
                            onAudioCall = { onStartCallback(item, false) },
                            onVideoCall = { onStartCallback(item, true) }
                        )
                    }
                }
            }
        }
    }

    pendingDeleteSingle?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDeleteSingle = null },
            title = { Text(stringResource(R.string.call_delete_one_confirm_title)) },
            text = { Text(stringResource(R.string.call_delete_one_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteCall(target.callId)
                        pendingDeleteSingle = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("confirm_delete_call_single_button")
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteSingle = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (showConfirmDeleteBatch) {
        AlertDialog(
            onDismissRequest = { showConfirmDeleteBatch = false },
            title = { Text(stringResource(R.string.call_delete_batch_confirm_title)) },
            text = {
                Text(stringResource(R.string.call_delete_batch_confirm_body, state.selectedCallIds.size))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteSelectedCalls()
                        showConfirmDeleteBatch = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("confirm_delete_call_batch_button")
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDeleteBatch = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (showConfirmClearAll) {
        AlertDialog(
            onDismissRequest = { showConfirmClearAll = false },
            title = { Text(stringResource(R.string.call_clear_all_confirm_title)) },
            text = { Text(stringResource(R.string.call_clear_all_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearAllHistory()
                        showConfirmClearAll = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("confirm_clear_all_calls_button")
                ) {
                    Text(stringResource(R.string.call_clear_all))
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmClearAll = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CallHistoryRow(
    item: CallHistoryItem,
    peerIsOnline: Boolean?,
    networkModule: NetworkModule,
    inSelectionMode: Boolean,
    isSelected: Boolean,
    onToggleSelect: () -> Unit,
    onDeleteSingle: () -> Unit,
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

    val containerColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    if (inSelectionMode) {
                        onToggleSelect()
                    }
                },
                onLongClick = onToggleSelect
            )
            .testTag("call_history_row_${item.callId}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (inSelectionMode) {
                IconButton(
                    onClick = onToggleSelect,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("select_call_checkbox_${item.callId}")
                ) {
                    Icon(
                        imageVector = if (isSelected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                        contentDescription = stringResource(R.string.call_select_multiple),
                        tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
            }
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
                    val callTypeLabel = if (item.isVideo) {
                        stringResource(R.string.call_video)
                    } else {
                        stringResource(R.string.call_audio)
                    }
                    val context = LocalContext.current
                    Text(
                        text = "$statusText • $callTypeLabel • ${DateTimeFormatters.formatFullDateTime(context, item.startedAtMs, AppLanguage.ENGLISH)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isMissedOrRejected) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (!inSelectionMode) {
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
                IconButton(
                    onClick = onDeleteSingle,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("delete_call_${item.callId}")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteOutline,
                        contentDescription = stringResource(R.string.call_delete_one),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}
