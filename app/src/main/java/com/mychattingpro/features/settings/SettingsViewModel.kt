package com.mychattingpro.features.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mychattingpro.core.common.AppError
import com.mychattingpro.core.datastore.AppPreferences
import com.mychattingpro.core.datastore.AppPreferencesDataStore
import com.mychattingpro.core.localization.AppLanguage
import com.mychattingpro.core.security.EncryptedTokenStore
import com.mychattingpro.core.security.StoredSession
import com.mychattingpro.core.theme.ThemePreference
import com.mychattingpro.data.repository.AppException
import com.mychattingpro.domain.models.ActiveDeviceSession
import com.mychattingpro.domain.models.BlockedUser
import com.mychattingpro.domain.models.OwnerActivitySnapshot
import com.mychattingpro.domain.repository.AuthRepository
import com.mychattingpro.domain.repository.OwnerRepository
import com.mychattingpro.domain.repository.SafetyRepository
import com.mychattingpro.features.notifications.RealtimeSyncService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val isLoggingOut: Boolean = false,
    val isDeletingAccount: Boolean = false,
    val deletionCompletedNotice: String? = null,
    val error: AppError? = null,
    val blockedUsers: List<BlockedUser> = emptyList(),
    val isLoadingBlocked: Boolean = false,
    val unblockingUserId: String? = null,
    val activeSessions: List<ActiveDeviceSession> = emptyList(),
    val isLoadingSessions: Boolean = false,
    val revokingSessionId: String? = null,
    val hasSavedOwnerKey: Boolean = false,
    val ownerActivity: OwnerActivitySnapshot? = null,
    val isLoadingOwnerActivity: Boolean = false,
    val isLoadingMoreOwnerUsers: Boolean = false,
    val ownerSearchQuery: String = "",
    val ownerError: AppError? = null
)

class SettingsViewModel(
    private val appContext: Context,
    private val prefsStore: AppPreferencesDataStore,
    private val tokenStore: EncryptedTokenStore,
    private val authRepository: AuthRepository,
    private val safetyRepository: SafetyRepository,
    private val ownerRepository: OwnerRepository
) : ViewModel() {

    val preferences: StateFlow<AppPreferences> = prefsStore.preferencesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppPreferences())

    val session: StateFlow<StoredSession?> = tokenStore.sessionFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val language: StateFlow<AppLanguage> = prefsStore.preferencesFlow
        .map { it.language }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppLanguage.ENGLISH)

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    fun setLanguage(language: AppLanguage) {
        viewModelScope.launch {
            prefsStore.setLanguage(language)
        }
    }

    fun setThemePreference(theme: ThemePreference) {
        viewModelScope.launch {
            prefsStore.setThemePreference(theme)
        }
    }

    fun setBackgroundSyncEnabled(enabled: Boolean) {
        viewModelScope.launch {
            prefsStore.setBackgroundSyncEnabled(enabled)
            if (enabled) {
                RealtimeSyncService.start(appContext)
            } else {
                RealtimeSyncService.stop(appContext)
            }
        }
    }

    fun logout(onDone: () -> Unit) {
        if (_uiState.value.isLoggingOut) return
        _uiState.value = _uiState.value.copy(isLoggingOut = true, error = null)
        viewModelScope.launch {
            RealtimeSyncService.stop(appContext)
            authRepository.logout()
            _uiState.value = _uiState.value.copy(isLoggingOut = false)
            onDone()
        }
    }

    fun deleteAccount(pin: String) {
        if (pin.length != 5 || !pin.all { it.isDigit() }) {
            _uiState.value = _uiState.value.copy(error = AppError.InvalidPin)
            return
        }
        if (_uiState.value.isDeletingAccount) return
        _uiState.value = _uiState.value.copy(isDeletingAccount = true, error = null)
        viewModelScope.launch {
            val result = authRepository.deleteAccount(pin)
            result.onSuccess { notice ->
                RealtimeSyncService.stop(appContext)
                _uiState.value = _uiState.value.copy(
                    isDeletingAccount = false,
                    deletionCompletedNotice = notice
                )
            }.onFailure { throwable ->
                val appErr = (throwable as? AppException)?.appError ?: AppError.ServerError()
                _uiState.value = _uiState.value.copy(
                    isDeletingAccount = false,
                    error = appErr
                )
            }
        }
    }

    fun acknowledgeDeletionAndFinish(onDone: () -> Unit) {
        _uiState.value = _uiState.value.copy(deletionCompletedNotice = null)
        onDone()
    }

    fun loadBlockedUsers() {
        _uiState.value = _uiState.value.copy(isLoadingBlocked = true, error = null)
        viewModelScope.launch {
            safetyRepository.getBlockedUsers()
                .onSuccess { users ->
                    _uiState.value = _uiState.value.copy(
                        blockedUsers = users,
                        isLoadingBlocked = false
                    )
                }
                .onFailure { throwable ->
                    val appErr = (throwable as? AppException)?.appError ?: AppError.ServerError()
                    _uiState.value = _uiState.value.copy(
                        isLoadingBlocked = false,
                        error = appErr
                    )
                }
        }
    }

    fun unblockUser(userId: String) {
        _uiState.value = _uiState.value.copy(unblockingUserId = userId, error = null)
        viewModelScope.launch {
            safetyRepository.unblockUser(userId)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        blockedUsers = _uiState.value.blockedUsers.filterNot { it.userId == userId },
                        unblockingUserId = null
                    )
                }
                .onFailure { throwable ->
                    val appErr = (throwable as? AppException)?.appError ?: AppError.ServerError()
                    _uiState.value = _uiState.value.copy(
                        unblockingUserId = null,
                        error = appErr
                    )
                }
        }
    }

    fun loadActiveSessions() {
        _uiState.value = _uiState.value.copy(isLoadingSessions = true, error = null)
        viewModelScope.launch {
            authRepository.getActiveSessions()
                .onSuccess { sessions ->
                    _uiState.value = _uiState.value.copy(
                        activeSessions = sessions,
                        isLoadingSessions = false
                    )
                }
                .onFailure { throwable ->
                    val appErr = (throwable as? AppException)?.appError ?: AppError.ServerError()
                    _uiState.value = _uiState.value.copy(
                        isLoadingSessions = false,
                        error = appErr
                    )
                }
        }
    }

    fun revokeSession(sessionId: String) {
        _uiState.value = _uiState.value.copy(revokingSessionId = sessionId, error = null)
        viewModelScope.launch {
            authRepository.revokeSession(sessionId)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        activeSessions = _uiState.value.activeSessions.filterNot { it.sessionId == sessionId },
                        revokingSessionId = null
                    )
                }
                .onFailure { throwable ->
                    val appErr = (throwable as? AppException)?.appError ?: AppError.ServerError()
                    _uiState.value = _uiState.value.copy(
                        revokingSessionId = null,
                        error = appErr
                    )
                }
        }
    }

    fun initOwnerMonitor() {
        viewModelScope.launch {
            val savedKey = tokenStore.getOwnerKey()
            if (!savedKey.isNullOrBlank()) {
                _uiState.value = _uiState.value.copy(hasSavedOwnerKey = true)
                fetchOwnerActivity(savedKey, page = 1, append = false)
            } else {
                _uiState.value = _uiState.value.copy(hasSavedOwnerKey = false, ownerActivity = null)
            }
        }
    }

    fun unlockAndLoadOwnerActivity(ownerKeyInput: String) {
        val key = ownerKeyInput.trim()
        if (key.isEmpty()) {
            _uiState.value = _uiState.value.copy(ownerError = AppError.InvalidOwnerKey)
            return
        }
        viewModelScope.launch {
            fetchOwnerActivity(key, page = 1, append = false, persistOnSuccess = true)
        }
    }

    fun updateOwnerSearchQuery(query: String) {
        _uiState.value = _uiState.value.copy(ownerSearchQuery = query)
    }

    fun searchOwnerUsers() {
        viewModelScope.launch {
            val key = tokenStore.getOwnerKey() ?: return@launch
            fetchOwnerActivity(key, page = 1, append = false)
        }
    }

    fun refreshOwnerActivity() {
        viewModelScope.launch {
            val key = tokenStore.getOwnerKey() ?: return@launch
            fetchOwnerActivity(key, page = 1, append = false)
        }
    }

    fun loadMoreOwnerUsers() {
        val current = _uiState.value.ownerActivity ?: return
        if (!current.hasMore || _uiState.value.isLoadingMoreOwnerUsers) return
        viewModelScope.launch {
            val key = tokenStore.getOwnerKey() ?: return@launch
            fetchOwnerActivity(key, page = current.page + 1, append = true)
        }
    }

    private suspend fun fetchOwnerActivity(
        ownerKey: String,
        page: Int,
        append: Boolean,
        persistOnSuccess: Boolean = false
    ) {
        _uiState.value = if (append) {
            _uiState.value.copy(isLoadingMoreOwnerUsers = true, ownerError = null)
        } else {
            _uiState.value.copy(isLoadingOwnerActivity = true, ownerError = null)
        }

        val query = _uiState.value.ownerSearchQuery
        ownerRepository.fetchOwnerActivity(ownerKey = ownerKey, query = query, page = page, limit = 20)
            .onSuccess { snapshot ->
                if (persistOnSuccess) {
                    tokenStore.saveOwnerKey(ownerKey)
                }
                val mergedSnapshot = if (append && _uiState.value.ownerActivity != null) {
                    val existing = _uiState.value.ownerActivity!!
                    val existingIds = existing.users.map { it.userId }.toSet()
                    val combinedUsers = existing.users + snapshot.users.filterNot { it.userId in existingIds }
                    snapshot.copy(users = combinedUsers)
                } else {
                    snapshot
                }
                _uiState.value = _uiState.value.copy(
                    hasSavedOwnerKey = true,
                    ownerActivity = mergedSnapshot,
                    isLoadingOwnerActivity = false,
                    isLoadingMoreOwnerUsers = false,
                    ownerError = null
                )
            }
            .onFailure { throwable ->
                val appErr = (throwable as? AppException)?.appError ?: AppError.ServerError()
                val shouldClearSavedKey = appErr is AppError.InvalidOwnerKey || appErr is AppError.OwnerAccessDenied
                if (shouldClearSavedKey) {
                    tokenStore.saveOwnerKey(null)
                }
                _uiState.value = _uiState.value.copy(
                    hasSavedOwnerKey = !shouldClearSavedKey && _uiState.value.hasSavedOwnerKey,
                    isLoadingOwnerActivity = false,
                    isLoadingMoreOwnerUsers = false,
                    ownerError = appErr
                )
            }
    }

    fun lockOwnerMonitor() {
        viewModelScope.launch {
            tokenStore.saveOwnerKey(null)
            _uiState.value = _uiState.value.copy(
                hasSavedOwnerKey = false,
                ownerActivity = null,
                ownerSearchQuery = "",
                ownerError = null
            )
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null, ownerError = null)
    }
}
