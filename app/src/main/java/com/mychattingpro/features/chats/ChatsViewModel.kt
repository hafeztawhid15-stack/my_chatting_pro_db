package com.mychattingpro.features.chats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mychattingpro.core.common.AppError
import com.mychattingpro.core.common.Country
import com.mychattingpro.core.common.CountryCatalog
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AddChatUiState(
    val selectedCountry: Country = CountryCatalog.findByIso("BD"),
    val nameFilter: String = "",
    val phoneInput: String = "",
    val isSearching: Boolean = false,
    val hasSearched: Boolean = false,
    val foundUser: PublicUser? = null,
    val error: AppError? = null,
    val isStartingChat: Boolean = false
)

class ChatsViewModel(
    private val chatRepository: ChatRepository,
    private val tokenStore: EncryptedTokenStore,
    private val prefsStore: AppPreferencesDataStore,
    private val networkMonitor: NetworkMonitor,
    private val socketManager: RealtimeSocketManager
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

    private var debouncedSearchJob: Job? = null

    init {
        socketManager.start()
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            chatRepository.refreshConversations()
        }
    }

    fun selectAddChatCountry(country: Country) {
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
        _addChatState.value = _addChatState.value.copy(phoneInput = phone, error = null)
        triggerDebouncedSearchIfPossible()
    }

    private fun triggerDebouncedSearchIfPossible() {
        debouncedSearchJob?.cancel()
        val state = _addChatState.value
        val digits = state.phoneInput.filter { it.isDigit() }
        if (digits.length < 7) return
        debouncedSearchJob = viewModelScope.launch {
            delay(500)
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
        val e164 = CountryCatalog.normalizeToE164(state.selectedCountry, state.phoneInput)
        if (e164 == null) {
            if (!silentInvalid) {
                _addChatState.value = state.copy(error = AppError.InvalidPhone, hasSearched = false)
            }
            return
        }

        _addChatState.value = state.copy(isSearching = true, error = null)
        val result = chatRepository.searchUser(e164, state.nameFilter.trim().ifEmpty { null })
        result.onSuccess { user ->
            _addChatState.value = _addChatState.value.copy(
                isSearching = false,
                hasSearched = true,
                foundUser = user
            )
        }.onFailure { err ->
            val appErr = (err as? AppException)?.appError ?: AppError.ServerError()
            _addChatState.value = _addChatState.value.copy(
                isSearching = false,
                error = appErr
            )
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
}
