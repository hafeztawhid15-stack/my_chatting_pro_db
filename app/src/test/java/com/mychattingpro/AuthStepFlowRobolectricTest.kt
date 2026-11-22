package com.mychattingpro

import android.Manifest
import android.app.Application
import android.content.Context
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.test.core.app.ApplicationProvider
import com.example.R
import com.mychattingpro.core.common.AppError
import com.mychattingpro.core.common.Country
import com.mychattingpro.core.common.CountryCatalog
import com.mychattingpro.core.datastore.AppPreferencesDataStore
import com.mychattingpro.core.localization.AppLanguage
import com.mychattingpro.core.localization.LocaleManager
import com.mychattingpro.core.security.AndroidSimVerificationProvider
import com.mychattingpro.core.security.SimCheckResult
import com.mychattingpro.core.security.SimVerificationProvider
import com.mychattingpro.data.repository.AppException
import com.mychattingpro.domain.models.ActiveDeviceSession
import com.mychattingpro.domain.models.UserProfile
import com.mychattingpro.domain.repository.AuthRepository
import com.mychattingpro.features.auth.AuthViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSubscriptionManager

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AuthStepFlowRobolectricTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeSimProvider(
        private var hasPerm: Boolean = true,
        private var result: SimCheckResult = SimCheckResult.VerifiedWithNumbers(
            numbersE164 = listOf("+8801712345678", "+8801811223344"),
            carriers = listOf("Grameenphone"),
            countryIsos = listOf("bd")
        )
    ) : SimVerificationProvider {
        override fun requiredPermissions(): Array<String> = arrayOf(
            Manifest.permission.READ_PHONE_NUMBERS,
            Manifest.permission.READ_PHONE_STATE
        )

        override fun hasSimPermission(): Boolean = hasPerm

        override fun checkSimState(): SimCheckResult = if (!hasPerm) SimCheckResult.PermissionRequired else result

        override fun verifyRegistrationNumber(selectedCountry: Country, phoneE164: String): AppError? {
            return when (val st = checkSimState()) {
                SimCheckResult.PermissionRequired -> AppError.SimPermissionRequired
                SimCheckResult.NoSim -> AppError.SimRequired
                is SimCheckResult.VerifiedWithNumbers -> if (phoneE164 in st.numbersE164) null else AppError.SimNumberMismatch
                is SimCheckResult.VerifiedCarrierOnly -> if (
                    st.countryIsos.isNotEmpty() && !st.countryIsos.any { it.equals(selectedCountry.iso2, true) }
                ) AppError.SimNumberMismatch else null
            }
        }

        override fun parseCountryAndLocalNumber(e164: String): Pair<Country, String>? {
            val bd = CountryCatalog.findByIso("BD")
            return if (e164.startsWith("+880")) {
                bd to "0${e164.removePrefix("+880")}"
            } else null
        }

        fun setState(hasPerm: Boolean, next: SimCheckResult) {
            this.hasPerm = hasPerm
            this.result = next
        }
    }

    private class FakeAuthRepo(
        private val existingPhones: MutableSet<String> = mutableSetOf(),
        private val validLoginPin: String = "12345"
    ) : AuthRepository {
        override suspend fun checkPhoneAvailable(phoneE164: String): Result<Boolean> {
            return if (phoneE164 in existingPhones) {
                Result.failure(AppException(AppError.DuplicateAccount))
            } else {
                Result.success(true)
            }
        }

        override suspend fun register(
            phoneE164: String,
            country: Country,
            pin: String,
            confirmPin: String,
            displayName: String
        ): Result<UserProfile> {
            if (phoneE164 in existingPhones) {
                return Result.failure(AppException(AppError.DuplicateAccount))
            }
            existingPhones += phoneE164
            return Result.success(
                UserProfile(
                    id = "u-${existingPhones.size}",
                    phoneE164 = phoneE164,
                    displayName = displayName.ifBlank { "User" },
                    about = "Available",
                    avatarMediaId = null
                )
            )
        }

        override suspend fun login(phoneE164: String, pin: String): Result<UserProfile> {
            if (phoneE164 !in existingPhones) {
                return Result.failure(AppException(AppError.AccountNotFound))
            }
            if (pin != validLoginPin) {
                return Result.failure(AppException(AppError.WrongPin))
            }
            return Result.success(
                UserProfile(
                    id = "existing-user-1",
                    phoneE164 = phoneE164,
                    displayName = "Returning User",
                    about = "Available",
                    avatarMediaId = null
                )
            )
        }

        override suspend fun logout(): Result<Unit> = Result.success(Unit)
        override suspend fun logoutAllDevices(keepCurrent: Boolean): Result<Unit> = Result.success(Unit)
        override suspend fun changeLoginPin(currentPin: String, newPin: String, confirmNewPin: String): Result<Unit> = Result.success(Unit)
        override suspend fun generateRecoveryCode(currentPin: String): Result<String> = Result.success("ABCD-EFGH-IJKL")
        override suspend fun resetLoginPinWithRecoveryCode(
            phoneE164: String,
            recoveryCode: String,
            newPin: String,
            confirmNewPin: String
        ): Result<UserProfile> = Result.failure(UnsupportedOperationException())
        override suspend fun deleteAccount(pin: String, confirmText: String): Result<String> = Result.success("deleted")
        override suspend fun getActiveSessions(): Result<List<ActiveDeviceSession>> = Result.success(emptyList())
        override suspend fun revokeSession(sessionId: String): Result<Unit> = Result.success(Unit)
        override suspend fun syncNotificationEndpoint(): Result<Unit> = Result.success(Unit)
    }

    @Test
    fun `login advances step by step and succeeds WITHOUT SIM using phone and 5-digit PIN`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val noSimProvider = FakeSimProvider(hasPerm = false, result = SimCheckResult.NoSim)
        val vm = AuthViewModel(
            authRepository = FakeAuthRepo(mutableSetOf("+8801712345678"), validLoginPin = "54321"),
            prefsStore = AppPreferencesDataStore(context),
            simVerifier = noSimProvider
        )

        assertEquals(1, vm.uiState.value.loginStep)

        // Invalid phone keeps user on step 1 with error
        vm.updatePhone("12")
        vm.advanceLoginStep()
        assertEquals(1, vm.uiState.value.loginStep)
        assertTrue(vm.uiState.value.error is AppError.InvalidPhone)

        // Registered BD number advances to step 2 even without SIM
        vm.updatePhone("01712345678")
        vm.advanceLoginStep()
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.loginStep)
        assertNull(vm.uiState.value.error)
        assertEquals("+8801712345678", vm.formattedPhonePreview())

        // Login succeeds without SIM with 5-digit Login PIN
        var loggedIn = false
        vm.updatePin("54321")
        vm.login { loggedIn = true }
        advanceUntilIdle()
        assertTrue(loggedIn)
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `login previous step clears PIN and prepareCreateFromLogin routes to Step 1 with SIM check`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val simProvider = FakeSimProvider(hasPerm = true, result = SimCheckResult.NoSim)
        val vm = AuthViewModel(
            authRepository = FakeAuthRepo(mutableSetOf("+8801712345678")),
            prefsStore = AppPreferencesDataStore(context),
            simVerifier = simProvider
        )

        vm.updatePhone("01712345678")
        vm.advanceLoginStep()
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.loginStep)
        vm.updatePin("12345")
        assertTrue(vm.previousLoginStep())
        assertEquals(1, vm.uiState.value.loginStep)
        assertEquals("", vm.uiState.value.pin)
        assertFalse(vm.previousLoginStep())

        // Unregistered number sends user to Step 1 of Create Account where SIM is checked
        vm.updatePhone("01812345678")
        vm.advanceLoginStep()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.error is AppError.AccountNotFound)

        vm.prepareCreateFromLogin()
        assertEquals(1, vm.uiState.value.createStep)
        assertEquals("+8801812345678", vm.formattedPhonePreview())
        assertTrue(vm.uiState.value.simStatus is SimCheckResult.NoSim)
    }

    @Test
    fun `no SIM or missing permission or number mismatch strictly blocks new account registration`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val simProvider = FakeSimProvider(hasPerm = false, result = SimCheckResult.NoSim)
        val vm = AuthViewModel(
            authRepository = FakeAuthRepo(),
            prefsStore = AppPreferencesDataStore(context),
            simVerifier = simProvider
        )

        // 1) Permission missing -> blocked at Step 1
        vm.updatePhone("01712345678")
        vm.advanceCreateStep()
        assertEquals(1, vm.uiState.value.createStep)
        assertTrue(vm.uiState.value.error is AppError.SimPermissionRequired)

        // 2) Permission granted, no SIM inserted -> blocked at Step 1 with SimRequired
        simProvider.setState(hasPerm = true, SimCheckResult.NoSim)
        vm.advanceCreateStep()
        assertEquals(1, vm.uiState.value.createStep)
        assertTrue(vm.uiState.value.error is AppError.SimRequired)

        // 3) SIM inserted with +8801712345678, trying different number -> SimNumberMismatch
        simProvider.setState(
            hasPerm = true,
            SimCheckResult.VerifiedWithNumbers(
                numbersE164 = listOf("+8801712345678"),
                carriers = listOf("Grameenphone"),
                countryIsos = listOf("bd")
            )
        )
        vm.updatePhone("01999888777")
        vm.advanceCreateStep()
        assertEquals(1, vm.uiState.value.createStep)
        assertTrue(vm.uiState.value.error is AppError.SimNumberMismatch)

        // 4) Matching SIM number advances and registers cleanly
        vm.updatePhone("01712345678")
        vm.advanceCreateStep()
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.createStep)
        vm.updateDisplayName("SIM Owner")
        vm.advanceCreateStep()
        vm.updatePin("54321")
        vm.advanceCreateStep()
        vm.updateConfirmPin("54321")

        // If SIM is removed right before final Step 4 submit, createAccount blocks again at Step 1
        simProvider.setState(hasPerm = true, SimCheckResult.NoSim)
        var created = false
        vm.createAccount { created = true }
        advanceUntilIdle()
        assertFalse(created)
        assertEquals(1, vm.uiState.value.createStep)
        assertTrue(vm.uiState.value.error is AppError.SimRequired)

        // Restore SIM and complete registration
        simProvider.setState(
            hasPerm = true,
            SimCheckResult.VerifiedWithNumbers(
                numbersE164 = listOf("+8801712345678"),
                carriers = listOf("Grameenphone"),
                countryIsos = listOf("bd")
            )
        )
        vm.createAccount { created = true }
        advanceUntilIdle()
        assertTrue(created)
    }

    @Test
    fun `create account advances through all 4 steps when SIM matches`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vm = AuthViewModel(FakeAuthRepo(), AppPreferencesDataStore(context), FakeSimProvider())

        assertEquals(1, vm.uiState.value.createStep)

        vm.updatePhone("01712345678")
        vm.advanceCreateStep()
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.createStep)

        vm.updateDisplayName("  Tawhid Shakh  ")
        vm.advanceCreateStep()
        assertEquals(3, vm.uiState.value.createStep)
        assertEquals("Tawhid Shakh", vm.uiState.value.displayName)

        vm.updatePin("123")
        vm.advanceCreateStep()
        assertEquals(3, vm.uiState.value.createStep)
        assertTrue(vm.uiState.value.error is AppError.InvalidPin)

        vm.updatePin("54321")
        vm.advanceCreateStep()
        assertEquals(4, vm.uiState.value.createStep)
        assertNull(vm.uiState.value.error)

        vm.updateConfirmPin("99999")
        assertTrue(vm.previousCreateStep())
        assertEquals(3, vm.uiState.value.createStep)
        assertEquals("", vm.uiState.value.confirmPin)
    }

    @Test
    fun `same phone number cannot create a second account at step 1 or step 4`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val existingSet = mutableSetOf("+8801712345678")
        val vm = AuthViewModel(FakeAuthRepo(existingSet), AppPreferencesDataStore(context), FakeSimProvider())

        vm.updatePhone("01712345678")
        vm.advanceCreateStep()
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.createStep)
        assertTrue(vm.uiState.value.error is AppError.DuplicateAccount)

        val raceSet = mutableSetOf<String>()
        val raceVm = AuthViewModel(FakeAuthRepo(raceSet), AppPreferencesDataStore(context), FakeSimProvider())
        raceVm.updatePhone("01811223344")
        raceVm.advanceCreateStep()
        advanceUntilIdle()
        assertEquals(2, raceVm.uiState.value.createStep)
        raceVm.updateDisplayName("Second Attempt")
        raceVm.advanceCreateStep()
        raceVm.updatePin("11223")
        raceVm.advanceCreateStep()
        raceVm.updateConfirmPin("11223")

        raceSet += "+8801811223344"
        var succeeded = false
        raceVm.createAccount { succeeded = true }
        advanceUntilIdle()

        assertFalse(succeeded)
        assertEquals(1, raceVm.uiState.value.createStep)
        assertTrue(raceVm.uiState.value.error is AppError.DuplicateAccount)
    }

    @Test
    fun `AndroidSimVerificationProvider reads active SIM via SubscriptionManager and blocks NoSim`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val provider = AndroidSimVerificationProvider(app)

        // Without granted permission -> PermissionRequired
        assertEquals(SimCheckResult.PermissionRequired, provider.checkSimState())

        // Grant phone permissions in Robolectric
        Shadows.shadowOf(app).grantPermissions(
            Manifest.permission.READ_PHONE_NUMBERS,
            Manifest.permission.READ_PHONE_STATE
        )

        val tm = app.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        Shadows.shadowOf(tm).setSimState(TelephonyManager.SIM_STATE_ABSENT)
        val sm = app.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
        Shadows.shadowOf(sm).setActiveSubscriptionInfoList(emptyList())

        assertEquals(SimCheckResult.NoSim, provider.checkSimState())

        // Simulate active SIM with carrier, country ISO, and phone number
        val subInfo: SubscriptionInfo = ShadowSubscriptionManager.SubscriptionInfoBuilder.newBuilder()
            .setId(1)
            .setSimSlotIndex(0)
            .setCarrierName("Robi")
            .setCountryIso("bd")
            .setNumber("+8801812345678")
            .buildSubscriptionInfo()
        Shadows.shadowOf(sm).setActiveSubscriptionInfoList(listOf(subInfo))
        Shadows.shadowOf(tm).setSimState(TelephonyManager.SIM_STATE_READY)

        val checked = provider.checkSimState()
        assertTrue(checked is SimCheckResult.VerifiedWithNumbers)
        val verified = checked as SimCheckResult.VerifiedWithNumbers
        assertTrue("+8801812345678" in verified.numbersE164)
        assertNull(provider.verifyRegistrationNumber(CountryCatalog.findByIso("BD"), "+8801812345678"))
        assertEquals(
            AppError.SimNumberMismatch,
            provider.verifyRegistrationNumber(CountryCatalog.findByIso("BD"), "+8801711111111")
        )
    }

    @Test
    fun `step and SIM strings exist across English, Bengali, and Arabic`() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        listOf(AppLanguage.ENGLISH, AppLanguage.BENGALI, AppLanguage.ARABIC).forEach { lang ->
            val ctx = LocaleManager.localizedContext(base, lang)
            assertTrue(ctx.getString(R.string.step_progress, 1, 4).isNotBlank())
            assertTrue(ctx.getString(R.string.auth_login_step1_title).isNotBlank())
            assertTrue(ctx.getString(R.string.auth_login_step2_title).isNotBlank())
            assertTrue(ctx.getString(R.string.auth_create_step1_title).isNotBlank())
            assertTrue(ctx.getString(R.string.auth_create_step2_title).isNotBlank())
            assertTrue(ctx.getString(R.string.auth_create_step3_title).isNotBlank())
            assertTrue(ctx.getString(R.string.auth_create_step4_title).isNotBlank())
            assertTrue(ctx.getString(R.string.err_sim_required).isNotBlank())
            assertTrue(ctx.getString(R.string.err_sim_number_mismatch).isNotBlank())
            assertTrue(ctx.getString(R.string.sim_verify_no_otp_note).isNotBlank())
        }
    }
}
