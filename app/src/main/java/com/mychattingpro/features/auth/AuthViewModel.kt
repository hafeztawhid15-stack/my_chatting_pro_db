package com.mychattingpro.features.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mychattingpro.core.common.AppError
import com.mychattingpro.core.common.Country
import com.mychattingpro.core.common.CountryCatalog
import com.mychattingpro.core.datastore.AppPreferencesDataStore
import com.mychattingpro.core.localization.AppLanguage
import com.mychattingpro.data.repository.AppException
import com.mychattingpro.domain.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AuthUiState(
    val selectedCountry: Country = CountryCatalog.findByIso("BD"),
    val phoneInput: String = "",
    val pin: String = "",
    val confirmPin: String = "",
    val displayName: String = "",
    val loginStep: Int = 1, // 1: Country & Phone, 2: 5-Digit PIN
    val createStep: Int = 1, // 1: Country & Phone, 2: Display Name, 3: Create PIN, 4: Confirm PIN
    val isLoading: Boolean = false,
    val error: AppError? = null
)

class AuthViewModel(
    private val authRepository: AuthRepository,
    private val prefsStore: AppPreferencesDataStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    val recentCountryIsos: StateFlow<List<String>> = prefsStore.preferencesFlow
        .map { it.recentCountryIsoCodes }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), listOf("BD", "US", "GB"))

    fun selectCountry(country: Country) {
        _uiState.value = _uiState.value.copy(selectedCountry = country, error = null)
        viewModelScope.launch {
            prefsStore.recordRecentCountry(country.iso2)
        }
    }

    fun updatePhone(phone: String) {
        _uiState.value = _uiState.value.copy(phoneInput = phone, error = null)
    }

    fun updatePin(pin: String) {
        _uiState.value = _uiState.value.copy(pin = pin, error = null)
    }

    fun updateConfirmPin(pin: String) {
        _uiState.value = _uiState.value.copy(confirmPin = pin, error = null)
    }

    fun updateDisplayName(name: String) {
        _uiState.value = _uiState.value.copy(displayName = name, error = null)
    }

    fun setLanguage(lang: AppLanguage) {
        viewModelScope.launch { prefsStore.setLanguage(lang) }
    }

    fun formattedPhonePreview(): String {
        val state = _uiState.value
        return CountryCatalog.normalizeToE164(state.selectedCountry, state.phoneInput)
            ?: "${state.selectedCountry.callingCode} ${state.phoneInput.trim()}".trim()
    }

    fun advanceLoginStep() {
        val state = _uiState.value
        val e164 = CountryCatalog.normalizeToE164(state.selectedCountry, state.phoneInput)
        if (e164 == null) {
            _uiState.value = state.copy(error = AppError.InvalidPhone)
            return
        }
        _uiState.value = state.copy(loginStep = 2, error = null)
    }

    fun previousLoginStep(): Boolean {
        val step = _uiState.value.loginStep
        return if (step > 1) {
            _uiState.value = _uiState.value.copy(loginStep = 1, pin = "", error = null)
            true
        } else {
            false
        }
    }

    fun login(onSuccess: () -> Unit) {
        val state = _uiState.value
        val e164 = CountryCatalog.normalizeToE164(state.selectedCountry, state.phoneInput)
        if (e164 == null) {
            _uiState.value = state.copy(loginStep = 1, error = AppError.InvalidPhone)
            return
        }
        if (state.pin.length != 5 || !state.pin.all { it.isDigit() }) {
            _uiState.value = state.copy(error = AppError.InvalidPin)
            return
        }

        _uiState.value = state.copy(isLoading = true, error = null)
        viewModelScope.launch {
            val result = authRepository.login(e164, state.pin)
            result.onSuccess {
                _uiState.value = _uiState.value.copy(isLoading = false, pin = "")
                onSuccess()
            }.onFailure { throwable ->
                val appErr = (throwable as? AppException)?.appError ?: AppError.ServerError()
                _uiState.value = _uiState.value.copy(isLoading = false, error = appErr)
            }
        }
    }

    fun advanceCreateStep() {
        val state = _uiState.value
        if (state.isLoading) return
        when (state.createStep) {
            1 -> {
                val e164 = CountryCatalog.normalizeToE164(state.selectedCountry, state.phoneInput)
                if (e164 == null) {
                    _uiState.value = state.copy(error = AppError.InvalidPhone)
                    return
                }
                _uiState.value = state.copy(isLoading = true, error = null)
                viewModelScope.launch {
                    authRepository.checkPhoneAvailable(e164)
                        .onSuccess { available ->
                            if (available) {
                                _uiState.value = _uiState.value.copy(
                                    isLoading = false,
                                    createStep = 2,
                                    error = null
                                )
                            } else {
                                _uiState.value = _uiState.value.copy(
                                    isLoading = false,
                                    createStep = 1,
                                    error = AppError.DuplicateAccount
                                )
                            }
                        }
                        .onFailure { throwable ->
                            val appErr = (throwable as? AppException)?.appError ?: AppError.ServerError()
                            _uiState.value = _uiState.value.copy(
                                isLoading = false,
                                createStep = 1,
                                error = appErr
                            )
                        }
                }
            }
            2 -> {
                _uiState.value = state.copy(
                    displayName = state.displayName.trim(),
                    createStep = 3,
                    error = null
                )
            }
            3 -> {
                if (state.pin.length != 5 || !state.pin.all { it.isDigit() }) {
                    _uiState.value = state.copy(error = AppError.InvalidPin)
                    return
                }
                _uiState.value = state.copy(createStep = 4, error = null)
            }
        }
    }

    fun previousCreateStep(): Boolean {
        val step = _uiState.value.createStep
        return when {
            step == 4 -> {
                _uiState.value = _uiState.value.copy(createStep = 3, confirmPin = "", error = null)
                true
            }
            step > 1 -> {
                _uiState.value = _uiState.value.copy(createStep = step - 1, error = null)
                true
            }
            else -> false
        }
    }

    fun jumpCreateToPhoneStep() {
        _uiState.value = _uiState.value.copy(createStep = 1, error = null)
    }

    fun createAccount(onSuccess: () -> Unit) {
        val state = _uiState.value
        val e164 = CountryCatalog.normalizeToE164(state.selectedCountry, state.phoneInput)
        if (e164 == null) {
            _uiState.value = state.copy(createStep = 1, error = AppError.InvalidPhone)
            return
        }
        if (state.pin.length != 5 || !state.pin.all { it.isDigit() }) {
            _uiState.value = state.copy(createStep = 3, error = AppError.InvalidPin)
            return
        }
        if (state.pin != state.confirmPin) {
            _uiState.value = state.copy(error = AppError.PinMismatch)
            return
        }

        _uiState.value = state.copy(isLoading = true, error = null)
        viewModelScope.launch {
            val result = authRepository.register(
                phoneE164 = e164,
                country = state.selectedCountry,
                pin = state.pin,
                confirmPin = state.confirmPin,
                displayName = state.displayName.trim()
            )
            result.onSuccess {
                _uiState.value = _uiState.value.copy(isLoading = false, pin = "", confirmPin = "")
                onSuccess()
            }.onFailure { throwable ->
                val appErr = (throwable as? AppException)?.appError ?: AppError.ServerError()
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    createStep = if (appErr is AppError.DuplicateAccount) 1 else _uiState.value.createStep,
                    error = appErr
                )
            }
        }
    }

    fun resetFlow() {
        _uiState.value = AuthUiState(selectedCountry = _uiState.value.selectedCountry)
    }
}
