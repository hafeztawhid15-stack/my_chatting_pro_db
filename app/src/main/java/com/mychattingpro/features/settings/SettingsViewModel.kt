package com.mychattingpro.features.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.R
import com.mychattingpro.core.common.AppError
import com.mychattingpro.core.common.Country
import com.mychattingpro.core.common.CountryCatalog
import com.mychattingpro.core.datastore.AppPreferences
import com.mychattingpro.core.datastore.AppPreferencesDataStore
import com.mychattingpro.core.localization.AppLanguage
import com.mychattingpro.core.security.EncryptedTokenStore
import com.mychattingpro.core.security.StoredSession
import com.mychattingpro.core.theme.ThemePreference
import com.mychattingpro.data.repository.AppException
import com.mychattingpro.domain.models.ActiveDeviceSession
import com.mychattingpro.domain.models.AppUpdateInfo
import com.mychattingpro.domain.models.BlockedUser
import com.mychattingpro.domain.models.OwnerActivitySnapshot
import com.mychattingpro.domain.repository.AuthRepository
import com.mychattingpro.domain.repository.OwnerRepository
import com.mychattingpro.domain.repository.PrivateChatRepository
import com.mychattingpro.domain.repository.SafetyRepository
import com.mychattingpro.features.notifications.BackgroundSyncJobService
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
    val securityNotice: String? = null,
    val blockedUsers: List<BlockedUser> = emptyList(),
    val isLoadingBlocked: Boolean = false,
    val unblockingUserId: String? = null,
    val activeSessions: List<ActiveDeviceSession> = emptyList(),
    val isLoadingSessions: Boolean = false,
    val revokingSessionId: String? = null,
    val isLoggingOutAllDevices: Boolean = false,
    val hasSavedOwnerKey: Boolean = false,
    val ownerActivity: OwnerActivitySnapshot? = null,
    val isLoadingOwnerActivity: Boolean = false,
    val isLoadingMoreOwnerUsers: Boolean = false,
    val ownerSearchQuery: String = "",
    val ownerError: AppError? = null,
    val hasPrivatePin: Boolean? = null,
    val isPrivatePinBusy: Boolean = false,
    val privatePinError: AppError? = null,
    val privatePinNoticeResId: Int? = null,
    // Login PIN Security & Recovery Code
    val isPinSecurityBusy: Boolean = false,
    val pinSecurityError: AppError? = null,
    val pinSecurityNoticeResId: Int? = null,
    val generatedRecoveryCode: String? = null,
    val resetPinSuccess: Boolean = false,
    // App Update Check
    val isCheckingUpdate: Boolean = false,
    val appUpdateInfo: AppUpdateInfo? = null,
    val updateCheckError: String? = null
)

class SettingsViewModel(
    private val appContext: Context,
    private val prefsStore: AppPreferencesDataStore,
    private val tokenStore: EncryptedTokenStore,
    private val authRepository: AuthRepository,
    private val safetyRepository: SafetyRepository,
    private val ownerRepository: OwnerRepository,
    private val privateChatRepository: PrivateChatRepository? = null,
    private val appUpdateChecker: AppUpdateChecker? = null
) : ViewModel() {

    val preferences: StateFlow<AppPreferences> = prefsStore.preferencesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppPreferences())

    val session: StateFlow<StoredSession?> = tokenStore.sessionFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val language: StateFlow<AppLanguage> = prefsStore.preferencesFlow
        .map { it.language }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppLanguage.ENGLISH)

    val recentCountryIsos: StateFlow<List<String>> = prefsStore.preferencesFlow
        .map { it.recentCountryIsoCodes }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), listOf("BD", "US", "GB"))

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    fun setLanguage(language: AppLanguage) {
        viewModelScope.launch { prefsStore.setLanguage(language) }
    }

    fun setThemePreference(theme: ThemePreference) {
        viewModelScope.launch { prefsStore.setThemePreference(theme) }
    }

    // 14 Notification Settings Mutators
    fun setNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch { prefsStore.setNotificationsEnabled(enabled) }
    }

    fun setMessageNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch { prefsStore.setMessageNotificationsEnabled(enabled) }
    }

    fun setPrivateChatNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch { prefsStore.setPrivateChatNotificationsEnabled(enabled) }
    }

    fun setShowMessagePreview(enabled: Boolean) {
        viewModelScope.launch { prefsStore.setShowMessagePreview(enabled) }
    }

    fun setMessageSoundEnabled(enabled: Boolean) {
        viewModelScope.launch { prefsStore.setMessageSoundEnabled(enabled) }
    }

    fun setVibrationEnabled(enabled: Boolean) {
        viewModelScope.launch { prefsStore.setVibrationEnabled(enabled) }
    }

    fun setInAppAlertsEnabled(enabled: Boolean) {
        viewModelScope.launch { prefsStore.setInAppAlertsEnabled(enabled) }
    }

    fun setCallNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch { prefsStore.setCallNotificationsEnabled(enabled) }
    }

    fun setMissedCallNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch { prefsStore.setMissedCallNotificationsEnabled(enabled) }
    }

    fun setCallVibrationEnabled(enabled: Boolean) {
        viewModelScope.launch { prefsStore.setCallVibrationEnabled(enabled) }
    }

    fun setSecurityAlertsEnabled(enabled: Boolean) {
        viewModelScope.launch { prefsStore.setSecurityAlertsEnabled(enabled) }
    }

    fun setAccountAlertsEnabled(enabled: Boolean) {
        viewModelScope.launch { prefsStore.setAccountAlertsEnabled(enabled) }
    }

    fun setBackgroundSyncEnabled(enabled: Boolean) {
        viewModelScope.launch {
            prefsStore.setBackgroundSyncEnabled(enabled)
            if (enabled) {
                RealtimeSyncService.start(appContext)
                BackgroundSyncJobService.schedule(appContext)
            } else {
                RealtimeSyncService.stop(appContext)
                BackgroundSyncJobService.cancel(appContext)
            }
        }
    }

    fun saveUnifiedPushConfig(enabled: Boolean, endpointUrl: String) {
        viewModelScope.launch {
            prefsStore.setUnifiedPushConfig(enabled, endpointUrl)
            authRepository.syncNotificationEndpoint()
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        securityNotice = appContext.getString(R.string.notif_push_synced)
                    )
                }
                .onFailure { throwable ->
                    val appErr = (throwable as? AppException)?.appError ?: AppError.ServerError()
                    _uiState.value = _uiState.value.copy(error = appErr)
                }
        }
    }

    fun checkForUpdates() {
        val checker = appUpdateChecker ?: return
        if (_uiState.value.isCheckingUpdate) return
        _uiState.value = _uiState.value.copy(
            isCheckingUpdate = true,
            appUpdateInfo = null,
            updateCheckError = null
        )
        viewModelScope.launch {
            checker.checkForUpdate()
                .onSuccess { info ->
                    _uiState.value = _uiState.value.copy(
                        isCheckingUpdate = false,
                        appUpdateInfo = info
                    )
                }
                .onFailure { err ->
                    _uiState.value = _uiState.value.copy(
                        isCheckingUpdate = false,
                        updateCheckError = err.message ?: appContext.getString(R.string.update_check_failed)
                    )
                }
        }
    }

    fun dismissUpdateDialog() {
        _uiState.value = _uiState.value.copy(appUpdateInfo = null, updateCheckError = null)
    }

    fun logout(onDone: () -> Unit) {
        if (_uiState.value.isLoggingOut) return
        _uiState.value = _uiState.value.copy(isLoggingOut = true, error = null)
        viewModelScope.launch {
            RealtimeSyncService.stop(appContext)
            BackgroundSyncJobService.cancel(appContext)
            authRepository.logout()
            _uiState.value = _uiState.value.copy(isLoggingOut = false)
            onDone()
        }
    }

    fun logoutAllDevices(keepCurrent: Boolean, onAllLoggedOut: () -> Unit = {}) {
        if (_uiState.value.isLoggingOutAllDevices) return
        _uiState.value = _uiState.value.copy(isLoggingOutAllDevices = true, error = null)
        viewModelScope.launch {
            authRepository.logoutAllDevices(keepCurrent)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        isLoggingOutAllDevices = false,
                        securityNotice = if (keepCurrent) {
                            appContext.getString(R.string.logout_other_devices_success)
                        } else {
                            appContext.getString(R.string.logout_all_devices_success)
                        }
                    )
                    if (!keepCurrent) {
                        RealtimeSyncService.stop(appContext)
                        BackgroundSyncJobService.cancel(appContext)
                        onAllLoggedOut()
                    } else {
                        loadActiveSessions()
                    }
                }
                .onFailure { throwable ->
                    val appErr = (throwable as? AppException)?.appError ?: AppError.ServerError()
                    _uiState.value = _uiState.value.copy(
                        isLoggingOutAllDevices = false,
                        error = appErr
                    )
                }
        }
    }

    fun changeLoginPin(currentPin: String, newPin: String, confirmNewPin: String) {
        if (currentPin.length != 5 || !currentPin.all { it.isDigit() } ||
            newPin.length != 5 || !newPin.all { it.isDigit() } ||
            confirmNewPin.length != 5 || !confirmNewPin.all { it.isDigit() }
        ) {
            _uiState.value = _uiState.value.copy(pinSecurityError = AppError.InvalidPin)
            return
        }
        if (newPin != confirmNewPin) {
            _uiState.value = _uiState.value.copy(pinSecurityError = AppError.PinMismatch)
            return
        }
        if (_uiState.value.isPinSecurityBusy) return
        _uiState.value = _uiState.value.copy(isPinSecurityBusy = true, pinSecurityError = null)
        viewModelScope.launch {
            authRepository.changeLoginPin(currentPin, newPin, confirmNewPin)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        isPinSecurityBusy = false,
                        pinSecurityNoticeResId = R.string.login_pin_changed_success
                    )
                }
                .onFailure { throwable ->
                    val appErr = (throwable as? AppException)?.appError ?: AppError.ServerError()
                    _uiState.value = _uiState.value.copy(
                        isPinSecurityBusy = false,
                        pinSecurityError = appErr
                    )
                }
        }
    }

    fun generateRecoveryCode(currentPin: String) {
        if (currentPin.length != 5 || !currentPin.all { it.isDigit() }) {
            _uiState.value = _uiState.value.copy(pinSecurityError = AppError.InvalidPin)
            return
        }
        if (_uiState.value.isPinSecurityBusy) return
        _uiState.value = _uiState.value.copy(isPinSecurityBusy = true, pinSecurityError = null)
        viewModelScope.launch {
            authRepository.generateRecoveryCode(currentPin)
                .onSuccess { code ->
                    _uiState.value = _uiState.value.copy(
                        isPinSecurityBusy = false,
                        generatedRecoveryCode = code,
                        pinSecurityNoticeResId = R.string.recovery_code_generated_notice
                    )
                }
                .onFailure { throwable ->
                    val appErr = (throwable as? AppException)?.appError ?: AppError.ServerError()
                    _uiState.value = _uiState.value.copy(
                        isPinSecurityBusy = false,
                        pinSecurityError = appErr
                    )
                }
        }
    }

    fun resetLoginPinWithRecoveryCode(
        country: Country,
        phoneInput: String,
        recoveryCode: String,
        newPin: String,
        confirmNewPin: String
    ) {
        val e164 = CountryCatalog.normalizeToE164(country, phoneInput)
        if (e164 == null) {
            _uiState.value = _uiState.value.copy(pinSecurityError = AppError.InvalidPhone)
            return
        }
        val cleanCode = recoveryCode.trim().uppercase()
        if (cleanCode.length < 8) {
            _uiState.value = _uiState.value.copy(pinSecurityError = AppError.InvalidRecoveryCode)
            return
        }
        if (newPin.length != 5 || !newPin.all { it.isDigit() } ||
            confirmNewPin.length != 5 || !confirmNewPin.all { it.isDigit() }
        ) {
            _uiState.value = _uiState.value.copy(pinSecurityError = AppError.InvalidPin)
            return
        }
        if (newPin != confirmNewPin) {
            _uiState.value = _uiState.value.copy(pinSecurityError = AppError.PinMismatch)
            return
        }
        if (_uiState.value.isPinSecurityBusy) return
        _uiState.value = _uiState.value.copy(isPinSecurityBusy = true, pinSecurityError = null)
        viewModelScope.launch {
            authRepository.resetLoginPinWithRecoveryCode(e164, cleanCode, newPin, confirmNewPin)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        isPinSecurityBusy = false,
                        resetPinSuccess = true,
                        pinSecurityNoticeResId = R.string.login_pin_reset_success
                    )
                }
                .onFailure { throwable ->
                    val appErr = (throwable as? AppException)?.appError ?: AppError.ServerError()
                    _uiState.value = _uiState.value.copy(
                        isPinSecurityBusy = false,
                        pinSecurityError = appErr
                    )
                }
        }
    }

    fun clearPinSecurityFeedback() {
        _uiState.value = _uiState.value.copy(
            pinSecurityError = null,
            pinSecurityNoticeResId = null,
            resetPinSuccess = false
        )
    }

    fun recordRecentCountry(iso2: String) {
        viewModelScope.launch { prefsStore.recordRecentCountry(iso2) }
    }

    fun deleteAccount(pin: String, confirmText: String = "DELETE") {
        if (pin.length != 5 || !pin.all { it.isDigit() }) {
            _uiState.value = _uiState.value.copy(error = AppError.InvalidPin)
            return
        }
        if (confirmText.trim().uppercase() != "DELETE") {
            _uiState.value = _uiState.value.copy(error = AppError.InvalidDeleteConfirmation)
            return
        }
        if (_uiState.value.isDeletingAccount) return
        _uiState.value = _uiState.value.copy(isDeletingAccount = true, error = null)
        viewModelScope.launch {
            val result = authRepository.deleteAccount(pin, confirmText.trim().uppercase())
            result.onSuccess { notice ->
                RealtimeSyncService.stop(appContext)
                BackgroundSyncJobService.cancel(appContext)
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

    fun loadPrivatePinStatus() {
        val repo = privateChatRepository ?: return
        viewModelScope.launch {
            repo.getPinStatus().onSuccess { status ->
                _uiState.value = _uiState.value.copy(hasPrivatePin = status.hasPrivatePin)
            }
        }
    }

    fun setupPrivatePinFromSettings(pin: String, confirmPin: String) {
        val repo = privateChatRepository ?: return
        if (pin.length != 5 || !pin.all { it.isDigit() } || confirmPin.length != 5 || !confirmPin.all { it.isDigit() }) {
            _uiState.value = _uiState.value.copy(privatePinError = AppError.InvalidPin)
            return
        }
        if (pin != confirmPin) {
            _uiState.value = _uiState.value.copy(privatePinError = AppError.PinMismatch)
            return
        }
        if (_uiState.value.isPrivatePinBusy) return
        _uiState.value = _uiState.value.copy(isPrivatePinBusy = true, privatePinError = null)
        viewModelScope.launch {
            repo.setupPrivatePin(pin, confirmPin)
                .onSuccess {
                    repo.lockPrivateChat()
                    _uiState.value = _uiState.value.copy(
                        hasPrivatePin = true,
                        isPrivatePinBusy = false,
                        privatePinError = null,
                        privatePinNoticeResId = R.string.private_pin_setup_success
                    )
                }
                .onFailure { throwable ->
                    val appErr = (throwable as? AppException)?.appError ?: AppError.ServerError()
                    _uiState.value = _uiState.value.copy(
                        isPrivatePinBusy = false,
                        privatePinError = appErr
                    )
                }
        }
    }

    fun changePrivatePin(oldPin: String, newPin: String, confirmNewPin: String) {
        val repo = privateChatRepository ?: return
        if (oldPin.length != 5 || !oldPin.all { it.isDigit() } ||
            newPin.length != 5 || !newPin.all { it.isDigit() } ||
            confirmNewPin.length != 5 || !confirmNewPin.all { it.isDigit() }
        ) {
            _uiState.value = _uiState.value.copy(privatePinError = AppError.InvalidPin)
            return
        }
        if (newPin != confirmNewPin) {
            _uiState.value = _uiState.value.copy(privatePinError = AppError.PinMismatch)
            return
        }
        if (_uiState.value.isPrivatePinBusy) return
        _uiState.value = _uiState.value.copy(isPrivatePinBusy = true, privatePinError = null)
        viewModelScope.launch {
            repo.changePrivatePin(oldPin, newPin, confirmNewPin)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        hasPrivatePin = true,
                        isPrivatePinBusy = false,
                        privatePinError = null,
                        privatePinNoticeResId = R.string.private_pin_change_success
                    )
                }
                .onFailure { throwable ->
                    val appErr = (throwable as? AppException)?.appError ?: AppError.ServerError()
                    _uiState.value = _uiState.value.copy(
                        isPrivatePinBusy = false,
                        privatePinError = appErr
                    )
                }
        }
    }

    fun resetPrivatePinWithLoginPin(loginPin: String, newPrivatePin: String, confirmNewPrivatePin: String) {
        val repo = privateChatRepository ?: return
        if (loginPin.length != 5 || !loginPin.all { it.isDigit() } ||
            newPrivatePin.length != 5 || !newPrivatePin.all { it.isDigit() } ||
            confirmNewPrivatePin.length != 5 || !confirmNewPrivatePin.all { it.isDigit() }
        ) {
            _uiState.value = _uiState.value.copy(privatePinError = AppError.InvalidPin)
            return
        }
        if (newPrivatePin != confirmNewPrivatePin) {
            _uiState.value = _uiState.value.copy(privatePinError = AppError.PinMismatch)
            return
        }
        if (_uiState.value.isPrivatePinBusy) return
        _uiState.value = _uiState.value.copy(isPrivatePinBusy = true, privatePinError = null)
        viewModelScope.launch {
            repo.resetPrivatePinWithAccountAuth(loginPin, newPrivatePin, confirmNewPrivatePin)
                .onSuccess {
                    repo.lockPrivateChat()
                    _uiState.value = _uiState.value.copy(
                        hasPrivatePin = true,
                        isPrivatePinBusy = false,
                        privatePinError = null,
                        privatePinNoticeResId = R.string.private_pin_reset_success
                    )
                }
                .onFailure { throwable ->
                    val appErr = (throwable as? AppException)?.appError ?: AppError.ServerError()
                    _uiState.value = _uiState.value.copy(
                        isPrivatePinBusy = false,
                        privatePinError = appErr
                    )
                }
        }
    }

    fun clearPrivatePinFeedback() {
        _uiState.value = _uiState.value.copy(privatePinError = null, privatePinNoticeResId = null)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(
            error = null,
            ownerError = null,
            privatePinError = null,
            securityNotice = null
        )
    }
}
