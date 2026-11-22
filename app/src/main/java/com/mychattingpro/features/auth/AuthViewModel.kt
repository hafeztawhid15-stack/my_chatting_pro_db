package com.mychattingpro.features.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mychattingpro.core.common.AppError
import com.mychattingpro.core.common.Country
import com.mychattingpro.core.common.CountryCatalog
import com.mychattingpro.core.datastore.AppPreferencesDataStore
import com.mychattingpro.core.localization.AppLanguage
import com.mychattingpro.core.security.SimCheckResult
import com.mychattingpro.core.security.SimVerificationProvider
import com.mychattingpro.data.repository.AppException
import com.mychattingpro.domain.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private object FailClosedNoSimProvider : SimVerificationProvider {
    override fun requiredPermissions(): Array<String> = arrayOf(
        android.Manifest.permission.READ_PHONE_NUMBERS,
        android.Manifest.permission.READ_PHONE_STATE
    )
    override fun hasSimPermission(): Boolean = false
    override fun checkSimState(): SimCheckResult = SimCheckResult.NoSim
    override fun verifyRegistrationNumber(selectedCountry: Country, phoneE164: String): AppError =
        AppError.SimRequired
    override fun parseCountryAndLocalNumber(e164: String): Pair<Country, String>? = null
}

data class AuthUiState(
    val selectedCountry: Country = CountryCatalog.findByIso("BD"),
    val phoneInput: String = "",
    val pin: String = "",
    val confirmPin: String = "",
    val displayName: String = "",
    val loginStep: Int = 1, // 1: Country & Phone, 2: 5-Digit PIN
    val createStep: Int = 1, // 1: SIM + Country & Phone, 2: Display Name, 3: Create PIN, 4: Confirm PIN
    val isLoading: Boolean = false,
    val error: AppError? = null,
    val simStatus: SimCheckResult = SimCheckResult.PermissionRequired,
    val hasSimPermission: Boolean = false,
    val verifiedSimNumbers: List<String> = emptyList(),
    val simCarriers: List<String> = emptyList(),
    val simCountryIsos: List<String> = emptyList()
)

class AuthViewModel(
    private val authRepository: AuthRepository,
    private val prefsStore: AppPreferencesDataStore,
    private val simVerifier: SimVerificationProvider = FailClosedNoSimProvider
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    val recentCountryIsos: StateFlow<List<String>> = prefsStore.preferencesFlow
        .map { it.recentCountryIsoCodes }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), listOf("BD", "US", "GB"))

    fun simRequiredPermissions(): Array<String> = simVerifier.requiredPermissions()

    fun refreshSimVerification(autoFillIfBlank: Boolean = true) {
        val hasPerm = simVerifier.hasSimPermission()
        val result = simVerifier.checkSimState()
        val numbers = (result as? SimCheckResult.VerifiedWithNumbers)?.numbersE164.orEmpty()
        val carriers = when (result) {
            is SimCheckResult.VerifiedWithNumbers -> result.carriers
            is SimCheckResult.VerifiedCarrierOnly -> result.carriers
            else -> emptyList()
        }
        val isos = when (result) {
            is SimCheckResult.VerifiedWithNumbers -> result.countryIsos
            is SimCheckResult.VerifiedCarrierOnly -> result.countryIsos
            else -> emptyList()
        }

        var nextState = _uiState.value.copy(
            simStatus = result,
            hasSimPermission = hasPerm,
            verifiedSimNumbers = numbers,
            simCarriers = carriers,
            simCountryIsos = isos,
            error = if (_uiState.value.error is AppError.SimPermissionRequired ||
                _uiState.value.error is AppError.SimRequired
            ) {
                null
            } else {
                _uiState.value.error
            }
        )

        if (autoFillIfBlank && nextState.phoneInput.isBlank()) {
            if (numbers.isNotEmpty()) {
                simVerifier.parseCountryAndLocalNumber(numbers.first())?.let { (country, local) ->
                    nextState = nextState.copy(selectedCountry = country, phoneInput = local)
                }
            } else if (isos.isNotEmpty()) {
                nextState = nextState.copy(selectedCountry = CountryCatalog.findByIso(isos.first()))
            }
        }
        _uiState.value = nextState
    }

    fun selectVerifiedSimNumber(e164: String) {
        val parsed = simVerifier.parseCountryAndLocalNumber(e164)
        if (parsed != null) {
            _uiState.value = _uiState.value.copy(
                selectedCountry = parsed.first,
                phoneInput = parsed.second,
                error = null
            )
            viewModelScope.launch {
                prefsStore.recordRecentCountry(parsed.first.iso2)
            }
        }
    }

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

    // Existing account login works WITHOUT SIM using Phone + 5-digit Login PIN
    fun advanceLoginStep() {
        val state = _uiState.value
        if (state.isLoading) return
        val e164 = CountryCatalog.normalizeToE164(state.selectedCountry, state.phoneInput)
        if (e164 == null) {
            _uiState.value = state.copy(error = AppError.InvalidPhone)
            return
        }
        _uiState.value = state.copy(isLoading = true, error = null)
        viewModelScope.launch {
            authRepository.checkPhoneAvailable(e164)
                .onSuccess {
                    // Phone is unregistered -> user must create a new account first
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        loginStep = 1,
                        error = AppError.AccountNotFound
                    )
                }
                .onFailure { throwable ->
                    val appErr = (throwable as? AppException)?.appError ?: AppError.ServerError()
                    if (appErr is AppError.DuplicateAccount) {
                        // Phone already has an account -> allow proceeding to 5-digit PIN entry
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            loginStep = 2,
                            error = null
                        )
                    } else {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            loginStep = 1,
                            error = appErr
                        )
                    }
                }
        }
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
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    loginStep = if (appErr is AppError.AccountNotFound) 1 else _uiState.value.loginStep,
                    error = appErr
                )
            }
        }
    }

    fun advanceCreateStep() {
        val state = _uiState.value
        if (state.isLoading) return
        when (state.createStep) {
            1 -> {
                refreshSimVerification(autoFillIfBlank = false)
                val updated = _uiState.value
                val e164 = CountryCatalog.normalizeToE164(updated.selectedCountry, updated.phoneInput)
                if (e164 == null) {
                    val simPreErr = when (updated.simStatus) {
                        SimCheckResult.PermissionRequired -> AppError.SimPermissionRequired
                        SimCheckResult.NoSim -> AppError.SimRequired
                        else -> AppError.InvalidPhone
                    }
                    _uiState.value = updated.copy(error = simPreErr)
                    return
                }

                val simErr = simVerifier.verifyRegistrationNumber(updated.selectedCountry, e164)
                if (simErr != null) {
                    _uiState.value = updated.copy(error = simErr)
                    return
                }

                _uiState.value = updated.copy(isLoading = true, error = null)
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
        // Re-verify SIM & number ownership right before backend registration (zero bypass possible)
        refreshSimVerification(autoFillIfBlank = false)
        val simErr = simVerifier.verifyRegistrationNumber(state.selectedCountry, e164)
        if (simErr != null) {
            _uiState.value = _uiState.value.copy(createStep = 1, error = simErr)
            return
        }
        if (state.pin.length != 5 || !state.pin.all { it.isDigit() }) {
            _uiState.value = _uiState.value.copy(createStep = 3, error = AppError.InvalidPin)
            return
        }
        if (state.pin != state.confirmPin) {
            _uiState.value = _uiState.value.copy(error = AppError.PinMismatch)
            return
        }

        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
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

    fun prepareCreateFromLogin() {
        val state = _uiState.value
        _uiState.value = AuthUiState(
            selectedCountry = state.selectedCountry,
            phoneInput = state.phoneInput,
            createStep = 1
        )
        refreshSimVerification(autoFillIfBlank = false)
    }

    fun resetFlow() {
        _uiState.value = AuthUiState(selectedCountry = _uiState.value.selectedCountry)
    }
}
