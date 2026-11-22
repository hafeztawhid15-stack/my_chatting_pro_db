package com.mychattingpro.features.privatechat

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import com.mychattingpro.domain.repository.PrivateChatRepository
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

enum class PinGateMode {
    LOADING,
    SETUP,
    UNLOCK,
    RESET
}

data class PrivatePinUiState(
    val mode: PinGateMode = PinGateMode.LOADING,
    val primaryPinInput: String = "",
    val confirmPinInput: String = "",
    val loginPinInput: String = "",
    val isSubmitting: Boolean = false,
    val error: AppError? = null,
    val statusLoadFailed: Boolean = false,
    val lockoutRemainingSeconds: Int = 0,
    val unlockedSuccessfully: Boolean = false
)

data class PrivateAddChatUiState(
    val selectedCountry: Country = CountryCatalog.findByIso("BD"),
    val nameFilter: String = "",
    val phoneInput: String = "",
    val isSearching: Boolean = false,
    val hasSearched: Boolean = false,
    val foundUser: PublicUser? = null,
    val error: AppError? = null,
    val isStartingChat: Boolean = false
)

class PrivateChatViewModel(
    private val privateChatRepository: PrivateChatRepository,
    private val tokenStore: EncryptedTokenStore,
    private val prefsStore: AppPreferencesDataStore,
    networkMonitor: NetworkMonitor,
    socketManager: RealtimeSocketManager,
    private val searchRateLimiter: SearchRateLimiter = SearchRateLimiter(),
    private val safetyRepository: SafetyRepository? = null
) : ViewModel() {

    val isUnlocked: StateFlow<Boolean> = privateChatRepository.isUnlocked

    val privateConversations: StateFlow<List<Conversation>> =
        privateChatRepository.observePrivateConversations()
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

    private val _pinState = MutableStateFlow(PrivatePinUiState())
    val pinState: StateFlow<PrivatePinUiState> = _pinState.asStateFlow()

    private val _addChatState = MutableStateFlow(PrivateAddChatUiState())
    val addChatState: StateFlow<PrivateAddChatUiState> = _addChatState.asStateFlow()

    private val _isRefreshingChats = MutableStateFlow(false)
    val isRefreshingChats: StateFlow<Boolean> = _isRefreshingChats.asStateFlow()

    private val _chatsError = MutableStateFlow<AppError?>(null)
    val chatsError: StateFlow<AppError?> = _chatsError.asStateFlow()

    private val _peerProfileModal = MutableStateFlow<PeerProfileModalState?>(null)
    val peerProfileModal: StateFlow<PeerProfileModalState?> = _peerProfileModal.asStateFlow()

    private val _actionNotice = MutableStateFlow<String?>(null)
    val actionNotice: StateFlow<String?> = _actionNotice.asStateFlow()

    private var lockoutTickerJob: Job? = null

    fun clearActionFeedback() {
        _actionNotice.value = null
        _chatsError.value = null
    }

    fun dismissPeerProfile() {
        _peerProfileModal.value = null
    }

    fun viewPeerProfile(context: Context, conversation: Conversation) {
        touchActivity()
        _peerProfileModal.value = PeerProfileModalState(isLoading = true)
        viewModelScope.launch {
            privateChatRepository.getPrivatePeerProfile(conversation.id)
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
        touchActivity()
        viewModelScope.launch {
            privateChatRepository.setPrivateMutedUntil(conversation.id, mutedUntilMs)
                .onSuccess {
                    _actionNotice.value = if (mutedUntilMs != null) "Notifications muted" else "Notifications unmuted"
                }
                .onFailure { err ->
                    _chatsError.value = (err as? AppException)?.appError ?: AppError.ServerError()
                }
        }
    }

    fun setCustomSound(conversation: Conversation, soundUri: String?, soundLabel: String?) {
        touchActivity()
        viewModelScope.launch {
            privateChatRepository.setPrivateCustomSound(conversation.id, soundUri, soundLabel)
                .onSuccess {
                    _actionNotice.value = soundLabel ?: "Default notification sound restored"
                }
                .onFailure { err ->
                    _chatsError.value = (err as? AppException)?.appError ?: AppError.ServerError()
                }
        }
    }

    fun togglePin(conversation: Conversation) {
        touchActivity()
        viewModelScope.launch {
            privateChatRepository.setPrivatePinned(conversation.id, !conversation.isPinned)
                .onFailure { err ->
                    _chatsError.value = (err as? AppException)?.appError ?: AppError.ServerError()
                }
        }
    }

    fun toggleMarkUnread(conversation: Conversation) {
        touchActivity()
        val currentlyUnread = conversation.unreadCount > 0 || conversation.markedUnread
        viewModelScope.launch {
            privateChatRepository.setPrivateMarkedUnread(conversation.id, !currentlyUnread)
                .onFailure { err ->
                    _chatsError.value = (err as? AppException)?.appError ?: AppError.ServerError()
                }
        }
    }

    fun toggleArchive(conversation: Conversation) {
        touchActivity()
        viewModelScope.launch {
            privateChatRepository.setPrivateArchived(conversation.id, !conversation.isArchived)
                .onSuccess {
                    _actionNotice.value = if (!conversation.isArchived) "Chat archived" else "Chat restored"
                }
                .onFailure { err ->
                    _chatsError.value = (err as? AppException)?.appError ?: AppError.ServerError()
                }
        }
    }

    fun clearChat(conversation: Conversation) {
        touchActivity()
        viewModelScope.launch {
            privateChatRepository.clearPrivateConversation(conversation.id)
                .onSuccess { _actionNotice.value = "Chat cleared" }
                .onFailure { err ->
                    _chatsError.value = (err as? AppException)?.appError ?: AppError.ServerError()
                }
        }
    }

    fun deleteChat(conversation: Conversation) {
        touchActivity()
        viewModelScope.launch {
            privateChatRepository.deletePrivateConversation(conversation.id)
                .onSuccess { _actionNotice.value = "Private chat deleted" }
                .onFailure { err ->
                    _chatsError.value = (err as? AppException)?.appError ?: AppError.ServerError()
                }
        }
    }

    fun toggleBlockPeer(conversation: Conversation) {
        touchActivity()
        val safety = safetyRepository ?: return
        viewModelScope.launch {
            val res = if (conversation.isBlockedByMe) {
                safety.unblockUser(conversation.peerUserId)
            } else {
                safety.blockUser(conversation.peerUserId)
            }
            res.onSuccess {
                refreshPrivateConversations()
                _actionNotice.value = if (conversation.isBlockedByMe) "User unblocked" else "User blocked"
            }.onFailure { err ->
                _chatsError.value = (err as? AppException)?.appError ?: AppError.ServerError()
            }
        }
    }

    fun reportPeer(conversation: Conversation, reason: String, details: String?) {
        touchActivity()
        val safety = safetyRepository ?: return
        viewModelScope.launch {
            safety.report(conversation.peerUserId, null, reason, details)
                .onSuccess { _actionNotice.value = "Report submitted." }
                .onFailure { err ->
                    _chatsError.value = (err as? AppException)?.appError ?: AppError.ServerError()
                }
        }
    }

    fun preparePinEntry() {
        privateChatRepository.lockPrivateChat()
        lockoutTickerJob?.cancel()
        _pinState.value = PrivatePinUiState(mode = PinGateMode.LOADING)
        loadPinStatus()
    }

    fun loadPinStatus() {
        _pinState.value = _pinState.value.copy(
            mode = PinGateMode.LOADING,
            error = null,
            statusLoadFailed = false
        )
        viewModelScope.launch {
            privateChatRepository.getPinStatus()
                .onSuccess { status ->
                    _pinState.value = PrivatePinUiState(
                        mode = if (status.hasPrivatePin) PinGateMode.UNLOCK else PinGateMode.SETUP,
                        lockoutRemainingSeconds = status.retryAfterSeconds.coerceAtLeast(0)
                    )
                    if (status.retryAfterSeconds > 0) {
                        startLockoutCountdown(status.retryAfterSeconds)
                    }
                }
                .onFailure { throwable ->
                    val appErr = (throwable as? AppException)?.appError ?: AppError.ServerError()
                    _pinState.value = _pinState.value.copy(
                        mode = PinGateMode.LOADING,
                        statusLoadFailed = true,
                        error = appErr
                    )
                }
        }
    }

    fun updatePrimaryPin(value: String) {
        _pinState.value = _pinState.value.copy(primaryPinInput = value, error = null)
    }

    fun updateConfirmPin(value: String) {
        _pinState.value = _pinState.value.copy(confirmPinInput = value, error = null)
    }

    fun updateLoginPin(value: String) {
        _pinState.value = _pinState.value.copy(loginPinInput = value, error = null)
    }

    fun switchToResetMode() {
        _pinState.value = _pinState.value.copy(
            mode = PinGateMode.RESET,
            primaryPinInput = "",
            confirmPinInput = "",
            loginPinInput = "",
            error = null
        )
    }

    fun switchBackToUnlockMode() {
        _pinState.value = _pinState.value.copy(
            mode = PinGateMode.UNLOCK,
            primaryPinInput = "",
            confirmPinInput = "",
            loginPinInput = "",
            error = null
        )
    }

    fun submitSetup() {
        val current = _pinState.value
        if (current.isSubmitting) return
        val pin = current.primaryPinInput
        val confirm = current.confirmPinInput
        if (pin.length != 5 || !pin.all { it.isDigit() } || confirm.length != 5 || !confirm.all { it.isDigit() }) {
            _pinState.value = current.copy(error = AppError.InvalidPin)
            return
        }
        if (pin != confirm) {
            _pinState.value = current.copy(error = AppError.PinMismatch)
            return
        }

        _pinState.value = current.copy(isSubmitting = true, error = null)
        viewModelScope.launch {
            privateChatRepository.setupPrivatePin(pin, confirm)
                .onSuccess {
                    _pinState.value = PrivatePinUiState(
                        mode = PinGateMode.UNLOCK,
                        unlockedSuccessfully = true
                    )
                    refreshPrivateConversations()
                }
                .onFailure { throwable ->
                    val appErr = (throwable as? AppException)?.appError ?: AppError.ServerError()
                    _pinState.value = _pinState.value.copy(
                        isSubmitting = false,
                        error = appErr
                    )
                    if (appErr is AppError.PrivatePinAlreadySet) {
                        loadPinStatus()
                    }
                }
        }
    }

    fun submitUnlock() {
        val current = _pinState.value
        if (current.isSubmitting || current.lockoutRemainingSeconds > 0) return
        val pin = current.primaryPinInput
        if (pin.length != 5 || !pin.all { it.isDigit() }) {
            _pinState.value = current.copy(error = AppError.InvalidPin)
            return
        }

        _pinState.value = current.copy(isSubmitting = true, error = null)
        viewModelScope.launch {
            privateChatRepository.unlockPrivateChat(pin)
                .onSuccess {
                    lockoutTickerJob?.cancel()
                    _pinState.value = PrivatePinUiState(
                        mode = PinGateMode.UNLOCK,
                        unlockedSuccessfully = true
                    )
                    refreshPrivateConversations()
                }
                .onFailure { throwable ->
                    val appErr = (throwable as? AppException)?.appError ?: AppError.ServerError()
                    val lockSeconds = (appErr as? AppError.PrivatePinLocked)?.retryAfterSeconds ?: 0
                    _pinState.value = _pinState.value.copy(
                        isSubmitting = false,
                        primaryPinInput = "",
                        error = appErr,
                        lockoutRemainingSeconds = lockSeconds
                    )
                    if (lockSeconds > 0) {
                        startLockoutCountdown(lockSeconds)
                    } else if (appErr is AppError.PrivatePinNotSet) {
                        loadPinStatus()
                    }
                }
        }
    }

    fun submitReset() {
        val current = _pinState.value
        if (current.isSubmitting) return
        val loginPin = current.loginPinInput
        val newPin = current.primaryPinInput
        val confirmPin = current.confirmPinInput

        if (loginPin.length != 5 || !loginPin.all { it.isDigit() } ||
            newPin.length != 5 || !newPin.all { it.isDigit() } ||
            confirmPin.length != 5 || !confirmPin.all { it.isDigit() }
        ) {
            _pinState.value = current.copy(error = AppError.InvalidPin)
            return
        }
        if (newPin != confirmPin) {
            _pinState.value = current.copy(error = AppError.PinMismatch)
            return
        }

        _pinState.value = current.copy(isSubmitting = true, error = null)
        viewModelScope.launch {
            privateChatRepository.resetPrivatePinWithAccountAuth(loginPin, newPin, confirmPin)
                .onSuccess {
                    lockoutTickerJob?.cancel()
                    _pinState.value = PrivatePinUiState(
                        mode = PinGateMode.UNLOCK,
                        unlockedSuccessfully = true
                    )
                    refreshPrivateConversations()
                }
                .onFailure { throwable ->
                    val appErr = (throwable as? AppException)?.appError ?: AppError.ServerError()
                    _pinState.value = _pinState.value.copy(
                        isSubmitting = false,
                        error = appErr
                    )
                }
        }
    }

    fun consumeUnlockedNavigation() {
        if (_pinState.value.unlockedSuccessfully) {
            _pinState.value = _pinState.value.copy(unlockedSuccessfully = false)
        }
    }

    fun refreshPrivateConversations() {
        viewModelScope.launch {
            _isRefreshingChats.value = true
            _chatsError.value = null
            privateChatRepository.refreshPrivateConversations()
                .onFailure { throwable ->
                    val appErr = (throwable as? AppException)?.appError ?: AppError.ServerError()
                    _chatsError.value = appErr
                }
            _isRefreshingChats.value = false
        }
    }

    fun touchActivity() {
        privateChatRepository.touchActivity()
    }

    fun lockNow() {
        privateChatRepository.lockPrivateChat()
    }

    fun selectAddChatCountry(country: Country) {
        touchActivity()
        _addChatState.value = _addChatState.value.copy(
            selectedCountry = country,
            error = null,
            hasSearched = false,
            foundUser = null
        )
        viewModelScope.launch { prefsStore.recordRecentCountry(country.iso2) }
    }

    fun updateAddChatNameFilter(name: String) {
        touchActivity()
        _addChatState.value = _addChatState.value.copy(nameFilter = name)
    }

    fun updateAddChatPhone(phone: String) {
        touchActivity()
        _addChatState.value = _addChatState.value.copy(
            phoneInput = phone,
            error = null,
            hasSearched = false,
            foundUser = null
        )
    }

    fun resetAddChatModal() {
        touchActivity()
        _addChatState.value = PrivateAddChatUiState(
            selectedCountry = _addChatState.value.selectedCountry
        )
    }

    fun searchUserForPrivateChat() {
        touchActivity()
        val state = _addChatState.value
        if (state.isSearching) return

        val remainingBlock = searchRateLimiter.remainingBlockSeconds()
        if (remainingBlock > 0) {
            _addChatState.value = state.copy(
                error = AppError.RateLimited(remainingBlock),
                hasSearched = false,
                foundUser = null
            )
            return
        }

        val e164 = CountryCatalog.normalizeToE164(state.selectedCountry, state.phoneInput)
        if (e164 == null) {
            _addChatState.value = state.copy(
                error = AppError.InvalidPhone,
                hasSearched = false,
                foundUser = null
            )
            return
        }

        val myPhone = session.value?.phoneE164
        if (myPhone != null && myPhone == e164) {
            _addChatState.value = state.copy(
                error = AppError.SelfSearchNotAllowed,
                hasSearched = false,
                foundUser = null
            )
            return
        }

        val queryKey = "$e164|${state.nameFilter.trim()}"
        val allowRetry = !state.hasSearched || state.error != null
        when (val throttle = searchRateLimiter.checkAndRecord(queryKey, bypassDuplicateCheck = allowRetry)) {
            is SearchThrottleResult.RateLimited -> {
                _addChatState.value = state.copy(
                    error = AppError.RateLimited(throttle.retryAfterSeconds),
                    hasSearched = false,
                    foundUser = null
                )
                return
            }
            SearchThrottleResult.DuplicateTooSoon -> return
            SearchThrottleResult.Allowed -> Unit
        }

        _addChatState.value = state.copy(isSearching = true, error = null, foundUser = null)
        viewModelScope.launch {
            val activePhone = myPhone ?: tokenStore.getSession()?.phoneE164
            if (activePhone != null && activePhone == e164) {
                _addChatState.value = _addChatState.value.copy(
                    isSearching = false,
                    error = AppError.SelfSearchNotAllowed,
                    hasSearched = false,
                    foundUser = null
                )
                return@launch
            }
            privateChatRepository.searchUserForPrivateChat(e164, state.nameFilter.trim().ifEmpty { null })
                .onSuccess { user ->
                    _addChatState.value = _addChatState.value.copy(
                        isSearching = false,
                        hasSearched = true,
                        foundUser = user,
                        error = null
                    )
                }
                .onFailure { throwable ->
                    val appErr = (throwable as? AppException)?.appError ?: AppError.ServerError()
                    if (appErr is AppError.RateLimited) {
                        searchRateLimiter.recordServerRateLimit(appErr.retryAfterSeconds)
                    }
                    _addChatState.value = _addChatState.value.copy(
                        isSearching = false,
                        hasSearched = false,
                        foundUser = null,
                        error = appErr
                    )
                }
        }
    }

    fun startPrivateChatWithFoundUser(onOpened: (Conversation) -> Unit) {
        touchActivity()
        val target = _addChatState.value.foundUser ?: return
        _addChatState.value = _addChatState.value.copy(isStartingChat = true, error = null)
        viewModelScope.launch {
            privateChatRepository.startPrivateDirectChat(target.id)
                .onSuccess { conv ->
                    _addChatState.value = _addChatState.value.copy(isStartingChat = false)
                    onOpened(conv)
                }
                .onFailure { throwable ->
                    val appErr = (throwable as? AppException)?.appError ?: AppError.ServerError()
                    _addChatState.value = _addChatState.value.copy(
                        isStartingChat = false,
                        error = appErr
                    )
                }
        }
    }

    private fun startLockoutCountdown(seconds: Int) {
        lockoutTickerJob?.cancel()
        lockoutTickerJob = viewModelScope.launch {
            var remaining = seconds
            while (isActive && remaining > 0) {
                delay(1_000L)
                remaining -= 1
                _pinState.value = _pinState.value.copy(
                    lockoutRemainingSeconds = remaining.coerceAtLeast(0),
                    error = if (remaining > 0) AppError.PrivatePinLocked(remaining) else null
                )
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        lockoutTickerJob?.cancel()
    }
}
