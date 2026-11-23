package com.mychattingpro.features.chats

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.R
import com.mychattingpro.core.common.AppError
import com.mychattingpro.core.common.Country
import com.mychattingpro.core.common.CountryCatalog
import com.mychattingpro.core.common.SearchRateLimiter
import com.mychattingpro.core.common.SearchThrottleResult
import com.mychattingpro.core.datastore.AppPreferencesDataStore
import com.mychattingpro.core.localization.AppLanguage
import com.mychattingpro.core.network.NetworkMonitor
import com.mychattingpro.core.network.RealtimeSocketManager
import com.mychattingpro.core.network.SocketConnectionState
import com.mychattingpro.core.security.EncryptedTokenStore
import com.mychattingpro.core.security.StoredSession
import com.mychattingpro.data.repository.AppException
import com.mychattingpro.domain.models.Conversation
import com.mychattingpro.domain.models.PublicUser
import com.mychattingpro.domain.repository.ChatRepository
import com.mychattingpro.domain.repository.SafetyRepository
import com.mychattingpro.features.common.PeerProfileModalState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class AddChatUiState(
    val selectedCountry: Country = CountryCatalog.findByIso("BD"),
    val nameFilter: String = "",
    val phoneInput: String = "",
    val isSearching: Boolean = false,
    val hasSearched: Boolean = false,
    val foundUser: PublicUser? = null,
    val error: AppError? = null,
    val isStartingChat: Boolean = false,
    val rateLimitSecondsRemaining: Int = 0
)

class ChatsViewModel(
    private val chatRepository: ChatRepository,
    private val tokenStore: EncryptedTokenStore,
    private val prefsStore: AppPreferencesDataStore,
    private val networkMonitor: NetworkMonitor,
    private val socketManager: RealtimeSocketManager,
    private val searchRateLimiter: SearchRateLimiter = SearchRateLimiter(),
    private val safetyRepository: SafetyRepository? = null
) : ViewModel() {

    val conversations: StateFlow<List<Conversation>> = chatRepository.observeConversations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val session: StateFlow<StoredSession?> = tokenStore.sessionFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val language: StateFlow<AppLanguage> = prefsStore.preferencesFlow
        .map { it.language }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppLanguage.ENGLISH)

    val recentCountryIsos: StateFlow<List<String>> = prefsStore.preferencesFlow
        .map { it.recentCountryIsoCodes }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), listOf("BD", "US", "GB"))

    val isOnline: StateFlow<Boolean> = networkMonitor.isOnline
    val socketState: StateFlow<SocketConnectionState> = socketManager.connectionState

    private val _addChatState = MutableStateFlow(AddChatUiState())
    val addChatState: StateFlow<AddChatUiState> = _addChatState.asStateFlow()

    private val _peerProfileModal = MutableStateFlow<PeerProfileModalState?>(null)
    val peerProfileModal: StateFlow<PeerProfileModalState?> = _peerProfileModal.asStateFlow()

    private val _actionNotice = MutableStateFlow<String?>(null)
    val actionNotice: StateFlow<String?> = _actionNotice.asStateFlow()

    private val _actionError = MutableStateFlow<AppError?>(null)
    val actionError: StateFlow<AppError?> = _actionError.asStateFlow()

    private var debouncedSearchJob: Job? = null
    private var rateLimitJob: Job? = null

    init {
        socketManager.start()
        refresh()
        viewModelScope.launch {
            while (isActive) {
                delay(25_000L)
                if (networkMonitor.isOnline.value) {
                    socketManager.sendPresencePing()
                    chatRepository.refreshConversations()
                }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            socketManager.sendPresencePing()
            chatRepository.refreshConversations()
        }
    }

    fun clearActionFeedback() {
        _actionNotice.value = null
        _actionError.value = null
    }

    fun dismissPeerProfile() {
        _peerProfileModal.value = null
    }

    fun viewPeerProfile(context: Context, conversation: Conversation) {
        _peerProfileModal.value = PeerProfileModalState(isLoading = true)
        viewModelScope.launch {
            chatRepository.getPeerProfile(conversation.id)
                .onSuccess { profile ->
                    _peerProfileModal.value = PeerProfileModalState(isLoading = false, profile = profile)
                }
                .onFailure { err ->
                    val appErr = (err as? AppException)?.appError ?: AppError.ServerError()
                    _peerProfileModal.value = PeerProfileModalState(
                        isLoading = false,
                        errorMessage = appErr.toLocalizedMessage(context)
                    )
                }
        }
    }

    fun setMutedUntil(conversation: Conversation, mutedUntilMs: Long?) {
        viewModelScope.launch {
            chatRepository.setMutedUntil(conversation.id, mutedUntilMs)
                .onSuccess {
                    _actionNotice.value = if (mutedUntilMs != null) "Notifications muted" else "Notifications unmuted"
                }
                .onFailure { err ->
                    _actionError.value = (err as? AppException)?.appError ?: AppError.ServerError()
                }
        }
    }

    fun setCustomSound(conversation: Conversation, soundUri: String?, soundLabel: String?) {
        viewModelScope.launch {
            chatRepository.setCustomSound(conversation.id, soundUri, soundLabel)
                .onSuccess {
                    _actionNotice.value = soundLabel ?: "Default notification sound restored"
                }
                .onFailure { err ->
                    _actionError.value = (err as? AppException)?.appError ?: AppError.ServerError()
                }
        }
    }

    fun togglePin(conversation: Conversation) {
        viewModelScope.launch {
            chatRepository.setPinned(conversation.id, !conversation.isPinned)
                .onFailure { err ->
                    _actionError.value = (err as? AppException)?.appError ?: AppError.ServerError()
                }
        }
    }

    fun toggleMarkUnread(conversation: Conversation) {
        val currentlyUnread = conversation.unreadCount > 0 || conversation.markedUnread
        viewModelScope.launch {
            chatRepository.setMarkedUnread(conversation.id, !currentlyUnread)
                .onFailure { err ->
                    _actionError.value = (err as? AppException)?.appError ?: AppError.ServerError()
                }
        }
    }

    fun toggleArchive(conversation: Conversation) {
        viewModelScope.launch {
            chatRepository.setArchived(conversation.id, !conversation.isArchived)
                .onSuccess {
                    _actionNotice.value = if (!conversation.isArchived) "Chat archived" else "Chat restored"
                }
                .onFailure { err ->
                    _actionError.value = (err as? AppException)?.appError ?: AppError.ServerError()
                }
        }
    }

    fun clearChat(conversation: Conversation) {
        viewModelScope.launch {
            chatRepository.clearConversation(conversation.id)
                .onSuccess { _actionNotice.value = "Chat cleared" }
                .onFailure { err ->
                    _actionError.value = (err as? AppException)?.appError ?: AppError.ServerError()
                }
        }
    }

    fun deleteChat(conversation: Conversation) {
        viewModelScope.launch {
            chatRepository.deleteConversation(conversation.id)
                .onSuccess { _actionNotice.value = "Chat deleted" }
                .onFailure { err ->
                    _actionError.value = (err as? AppException)?.appError ?: AppError.ServerError()
                }
        }
    }

    fun moveToPrivateChat(context: Context, conversation: Conversation) {
        viewModelScope.launch {
            chatRepository.moveToPrivateChat(conversation.id)
                .onSuccess {
                    _actionNotice.value = context.getString(R.string.chat_moved_to_private_notice)
                }
                .onFailure { err ->
                    _actionError.value = (err as? AppException)?.appError ?: AppError.ServerError()
                }
        }
    }

    fun toggleBlockPeer(conversation: Conversation) {
        val safety = safetyRepository ?: return
        viewModelScope.launch {
            val res = if (conversation.isBlockedByMe) {
                safety.unblockUser(conversation.peerUserId)
            } else {
                safety.blockUser(conversation.peerUserId)
            }
            res.onSuccess {
                chatRepository.refreshConversations()
                _actionNotice.value = if (conversation.isBlockedByMe) "User unblocked" else "User blocked"
            }.onFailure { err ->
                _actionError.value = (err as? AppException)?.appError ?: AppError.ServerError()
            }
        }
    }

    fun reportPeer(conversation: Conversation, reason: String, details: String?) {
        val safety = safetyRepository ?: return
        viewModelScope.launch {
            safety.report(conversation.peerUserId, null, reason, details)
                .onSuccess { _actionNotice.value = "Report submitted." }
                .onFailure { err ->
                    _actionError.value = (err as? AppException)?.appError ?: AppError.ServerError()
                }
        }
    }

    fun selectAddChatCountry(country: Country) {
        debouncedSearchJob?.cancel()
        _addChatState.value = _addChatState.value.copy(
            selectedCountry = country,
            error = null,
            hasSearched = false,
            foundUser = null
        )
        viewModelScope.launch { prefsStore.recordRecentCountry(country.iso2) }
    }

    fun updateAddChatNameFilter(name: String) {
        _addChatState.value = _addChatState.value.copy(nameFilter = name)
        triggerDebouncedSearchIfPossible()
    }

    fun updateAddChatPhone(phone: String) {
        _addChatState.value = _addChatState.value.copy(
            phoneInput = phone,
            error = if (_addChatState.value.rateLimitSecondsRemaining > 0) _addChatState.value.error else null,
            hasSearched = false,
            foundUser = null
        )
        triggerDebouncedSearchIfPossible()
    }

    private fun triggerDebouncedSearchIfPossible() {
        debouncedSearchJob?.cancel()
        val state = _addChatState.value
        if (state.rateLimitSecondsRemaining > 0) return
        val normalized = CountryCatalog.normalizeToE164(state.selectedCountry, state.phoneInput)
            ?: return
        val myPhone = session.value?.phoneE164
        if (myPhone != null && myPhone == normalized) return

        debouncedSearchJob = viewModelScope.launch {
            delay(850)
            searchUserInternal(silentInvalid = true)
        }
    }

    fun searchUser() {
        debouncedSearchJob?.cancel()
        viewModelScope.launch {
            searchUserInternal(silentInvalid = false)
        }
    }

    private suspend fun searchUserInternal(silentInvalid: Boolean) {
        val state = _addChatState.value
        if (state.isSearching) return

        val remainingBlock = searchRateLimiter.remainingBlockSeconds()
        if (remainingBlock > 0) {
            if (!silentInvalid) {
                startRateLimitCountdown(remainingBlock)
            }
            return
        }

        val e164 = CountryCatalog.normalizeToE164(state.selectedCountry, state.phoneInput)
        if (e164 == null) {
            if (!silentInvalid) {
                _addChatState.value = state.copy(
                    error = AppError.InvalidPhone,
                    hasSearched = false,
                    foundUser = null
                )
            }
            return
        }

        val myPhone = session.value?.phoneE164 ?: tokenStore.getSession()?.phoneE164
        if (myPhone != null && myPhone == e164) {
            if (!silentInvalid) {
                _addChatState.value = state.copy(
                    error = AppError.SelfSearchNotAllowed,
                    hasSearched = false,
                    foundUser = null
                )
            }
            return
        }

        val nameFilterClean = state.nameFilter.trim()
        val queryKey = "$e164|$nameFilterClean"
        val allowManualRetry = !silentInvalid && (!state.hasSearched || state.error != null)
        when (val throttle = searchRateLimiter.checkAndRecord(queryKey, bypassDuplicateCheck = allowManualRetry)) {
            is SearchThrottleResult.RateLimited -> {
                if (!silentInvalid) {
                    startRateLimitCountdown(throttle.retryAfterSeconds)
                }
                return
            }
            SearchThrottleResult.DuplicateTooSoon -> return
            SearchThrottleResult.Allowed -> Unit
        }

        _addChatState.value = state.copy(
            isSearching = true,
            error = null,
            foundUser = null
        )
        val result = chatRepository.searchUser(e164, nameFilterClean.ifEmpty { null })
        result.onSuccess { user ->
            _addChatState.value = _addChatState.value.copy(
                isSearching = false,
                hasSearched = true,
                foundUser = user,
                error = null
            )
        }.onFailure { err ->
            val appErr = (err as? AppException)?.appError ?: AppError.ServerError()
            if (appErr is AppError.RateLimited) {
                searchRateLimiter.recordServerRateLimit(appErr.retryAfterSeconds)
                startRateLimitCountdown(appErr.retryAfterSeconds)
                _addChatState.value = _addChatState.value.copy(
                    isSearching = false,
                    hasSearched = false,
                    foundUser = null
                )
            } else {
                _addChatState.value = _addChatState.value.copy(
                    isSearching = false,
                    hasSearched = false,
                    foundUser = null,
                    error = appErr
                )
            }
        }
    }

    private fun startRateLimitCountdown(seconds: Int) {
        val safeSeconds = seconds.coerceAtLeast(1)
        rateLimitJob?.cancel()
        _addChatState.value = _addChatState.value.copy(
            rateLimitSecondsRemaining = safeSeconds,
            error = AppError.RateLimited(safeSeconds),
            hasSearched = false,
            foundUser = null
        )
        rateLimitJob = viewModelScope.launch {
            var remaining = safeSeconds
            while (isActive && remaining > 0) {
                delay(1_000L)
                remaining -= 1
                _addChatState.value = _addChatState.value.copy(
                    rateLimitSecondsRemaining = remaining.coerceAtLeast(0),
                    error = if (remaining > 0) AppError.RateLimited(remaining) else null
                )
            }
        }
    }

    fun startChatWithFoundUser(onOpened: (Conversation) -> Unit) {
        val target = _addChatState.value.foundUser ?: return
        _addChatState.value = _addChatState.value.copy(isStartingChat = true, error = null)
        viewModelScope.launch {
            val res = chatRepository.startDirectChat(target.id)
            res.onSuccess { conv ->
                _addChatState.value = _addChatState.value.copy(isStartingChat = false)
                onOpened(conv)
            }.onFailure { err ->
                val appErr = (err as? AppException)?.appError ?: AppError.ServerError()
                _addChatState.value = _addChatState.value.copy(isStartingChat = false, error = appErr)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        debouncedSearchJob?.cancel()
        rateLimitJob?.cancel()
    }
}
