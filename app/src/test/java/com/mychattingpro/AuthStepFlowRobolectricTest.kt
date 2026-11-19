package com.mychattingpro

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.R
import com.mychattingpro.core.common.AppError
import com.mychattingpro.core.common.Country
import com.mychattingpro.core.datastore.AppPreferencesDataStore
import com.mychattingpro.core.localization.AppLanguage
import com.mychattingpro.core.localization.LocaleManager
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
import org.robolectric.annotation.Config

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

    private class FakeAuthRepo(
        private val existingPhones: MutableSet<String> = mutableSetOf()
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

        override suspend fun login(phoneE164: String, pin: String): Result<UserProfile> =
            Result.failure(UnsupportedOperationException())

        override suspend fun logout(): Result<Unit> = Result.success(Unit)
        override suspend fun deleteAccount(pin: String): Result<String> = Result.success("deleted")
        override suspend fun getActiveSessions(): Result<List<ActiveDeviceSession>> = Result.success(emptyList())
        override suspend fun revokeSession(sessionId: String): Result<Unit> = Result.success(Unit)
    }

    @Test
    fun `login advances step by step and supports going back to phone step`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vm = AuthViewModel(FakeAuthRepo(), AppPreferencesDataStore(context))

        assertEquals(1, vm.uiState.value.loginStep)

        // Invalid phone keeps user on step 1 with error
        vm.updatePhone("12")
        vm.advanceLoginStep()
        assertEquals(1, vm.uiState.value.loginStep)
        assertTrue(vm.uiState.value.error is AppError.InvalidPhone)

        // Valid BD number advances to step 2
        vm.updatePhone("01712345678")
        vm.advanceLoginStep()
        assertEquals(2, vm.uiState.value.loginStep)
        assertNull(vm.uiState.value.error)
        assertEquals("+8801712345678", vm.formattedPhonePreview())

        // Previous step resets PIN and returns to step 1
        vm.updatePin("12345")
        assertTrue(vm.previousLoginStep())
        assertEquals(1, vm.uiState.value.loginStep)
        assertEquals("", vm.uiState.value.pin)
        assertFalse(vm.previousLoginStep())
    }

    @Test
    fun `create account advances through all 4 steps with per-step validation`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vm = AuthViewModel(FakeAuthRepo(), AppPreferencesDataStore(context))

        assertEquals(1, vm.uiState.value.createStep)

        // Step 1 -> Step 2 (Phone -> Display Name) after verifying availability
        vm.updatePhone("01712345678")
        vm.advanceCreateStep()
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.createStep)

        // Step 2 -> Step 3 (Display Name -> Create PIN)
        vm.updateDisplayName("  Tawhid Shakh  ")
        vm.advanceCreateStep()
        assertEquals(3, vm.uiState.value.createStep)
        assertEquals("Tawhid Shakh", vm.uiState.value.displayName)

        // Invalid PIN blocks Step 3 -> Step 4
        vm.updatePin("123")
        vm.advanceCreateStep()
        assertEquals(3, vm.uiState.value.createStep)
        assertTrue(vm.uiState.value.error is AppError.InvalidPin)

        // Valid 5-digit PIN advances Step 3 -> Step 4 (Confirm PIN)
        vm.updatePin("54321")
        vm.advanceCreateStep()
        assertEquals(4, vm.uiState.value.createStep)
        assertNull(vm.uiState.value.error)

        // Going back Step 4 -> Step 3 clears confirmPin
        vm.updateConfirmPin("99999")
        assertTrue(vm.previousCreateStep())
        assertEquals(3, vm.uiState.value.createStep)
        assertEquals("", vm.uiState.value.confirmPin)
    }

    @Test
    fun `same phone number cannot create a second account at step 1 or step 4`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val existingSet = mutableSetOf("+8801712345678")
        val vm = AuthViewModel(FakeAuthRepo(existingSet), AppPreferencesDataStore(context))

        // Attempting Step 1 with an already registered phone number is blocked immediately
        vm.updatePhone("01712345678")
        vm.advanceCreateStep()
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.createStep)
        assertTrue(vm.uiState.value.error is AppError.DuplicateAccount)

        // Simulate a race where the phone was registered while the user was on steps 2-4
        val raceSet = mutableSetOf<String>()
        val raceVm = AuthViewModel(FakeAuthRepo(raceSet), AppPreferencesDataStore(context))
        raceVm.updatePhone("01811223344")
        raceVm.advanceCreateStep()
        advanceUntilIdle()
        assertEquals(2, raceVm.uiState.value.createStep)
        raceVm.updateDisplayName("Second Attempt")
        raceVm.advanceCreateStep()
        raceVm.updatePin("11223")
        raceVm.advanceCreateStep()
        raceVm.updateConfirmPin("11223")

        // Someone else registered the same number before final submit
        raceSet += "+8801811223344"
        var succeeded = false
        raceVm.createAccount { succeeded = true }
        advanceUntilIdle()

        assertFalse(succeeded)
        assertEquals(1, raceVm.uiState.value.createStep)
        assertTrue(raceVm.uiState.value.error is AppError.DuplicateAccount)
    }

    @Test
    fun `step strings exist across English, Bengali, and Arabic`() {
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
        }
    }
}
