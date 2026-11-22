package com.mychattingpro.core.security

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.mychattingpro.core.common.AppError
import com.mychattingpro.core.common.Country
import com.mychattingpro.core.common.CountryCatalog
import java.util.Locale

sealed interface SimCheckResult {
    data class VerifiedWithNumbers(
        val numbersE164: List<String>,
        val carriers: List<String>,
        val countryIsos: List<String>
    ) : SimCheckResult

    data class VerifiedCarrierOnly(
        val carriers: List<String>,
        val countryIsos: List<String>
    ) : SimCheckResult

    data object NoSim : SimCheckResult

    data object PermissionRequired : SimCheckResult
}

interface SimVerificationProvider {
    fun requiredPermissions(): Array<String>
    fun hasSimPermission(): Boolean
    fun checkSimState(): SimCheckResult
    fun verifyRegistrationNumber(selectedCountry: Country, phoneE164: String): AppError?
    fun parseCountryAndLocalNumber(e164: String): Pair<Country, String>?
}

class AndroidSimVerificationProvider(
    private val context: Context
) : SimVerificationProvider {

    private val phoneUtil: PhoneNumberUtil = PhoneNumberUtil.getInstance()

    override fun requiredPermissions(): Array<String> = arrayOf(
        Manifest.permission.READ_PHONE_NUMBERS,
        Manifest.permission.READ_PHONE_STATE
    )

    override fun hasSimPermission(): Boolean {
        val hasNumbers = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_NUMBERS
        ) == PackageManager.PERMISSION_GRANTED
        val hasState = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
        return hasNumbers || hasState
    }

    @SuppressLint("MissingPermission", "HardwareIds")
    override fun checkSimState(): SimCheckResult {
        if (!hasSimPermission()) {
            return SimCheckResult.PermissionRequired
        }

        val telephonyManager = runCatching {
            context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        }.getOrNull() ?: return SimCheckResult.NoSim

        val subscriptionManager = runCatching {
            context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
        }.getOrNull()

        val activeSubs: List<SubscriptionInfo> = runCatching {
            subscriptionManager?.activeSubscriptionInfoList.orEmpty()
        }.getOrDefault(emptyList())

        val hasReadyTelephonySlot = hasReadySimSlot(telephonyManager)
        if (activeSubs.isEmpty() && !hasReadyTelephonySlot) {
            return SimCheckResult.NoSim
        }

        val verifiedNumbers = linkedSetOf<String>()
        val carriers = linkedSetOf<String>()
        val countryIsos = linkedSetOf<String>()

        activeSubs.forEach { sub ->
            val rawCarrier = sub.carrierName?.toString()?.trim().orEmpty()
            if (rawCarrier.isNotEmpty()) {
                carriers += rawCarrier
            }
            val subIso = sub.countryIso?.trim()?.lowercase(Locale.US).orEmpty()
            if (subIso.length == 2) {
                countryIsos += subIso
            }

            val rawCandidates = buildList {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    runCatching { subscriptionManager?.getPhoneNumber(sub.subscriptionId) }
                        .getOrNull()
                        ?.let { add(it) }
                }
                @Suppress("DEPRECATION")
                runCatching { sub.number }
                    .getOrNull()
                    ?.let { add(it) }

                runCatching {
                    telephonyManager.createForSubscriptionId(sub.subscriptionId).line1Number
                }.getOrNull()?.let { add(it) }
            }

            rawCandidates.forEach { candidate ->
                normalizeSimCandidate(candidate, subIso)?.let { verifiedNumbers += it }
            }
        }

        if (hasReadyTelephonySlot) {
            val tmIso = runCatching { telephonyManager.simCountryIso }.getOrNull()
                ?.trim()
                ?.lowercase(Locale.US)
                .orEmpty()
            if (tmIso.length == 2) {
                countryIsos += tmIso
            }
            val tmOperator = runCatching { telephonyManager.simOperatorName }.getOrNull()
                ?.trim()
                .orEmpty()
            if (tmOperator.isNotEmpty()) {
                carriers += tmOperator
            }
            val tmLine1 = runCatching { telephonyManager.line1Number }.getOrNull()
            if (!tmLine1.isNullOrBlank()) {
                normalizeSimCandidate(tmLine1, tmIso)?.let { verifiedNumbers += it }
            }
        }

        // If SubscriptionManager reported no active subscriptions AND line1/operator/country are all absent,
        // treat as NoSim unless at least one slot is genuinely ready with SIM identity metadata.
        val hasHardwareEvidence = activeSubs.isNotEmpty() ||
            verifiedNumbers.isNotEmpty() ||
            countryIsos.isNotEmpty() ||
            carriers.isNotEmpty() ||
            runCatching { telephonyManager.simOperator.isNotBlank() }.getOrDefault(false)

        if (!hasHardwareEvidence) {
            return SimCheckResult.NoSim
        }

        return if (verifiedNumbers.isNotEmpty()) {
            SimCheckResult.VerifiedWithNumbers(
                numbersE164 = verifiedNumbers.toList(),
                carriers = carriers.toList(),
                countryIsos = countryIsos.toList()
            )
        } else {
            SimCheckResult.VerifiedCarrierOnly(
                carriers = carriers.toList(),
                countryIsos = countryIsos.toList()
            )
        }
    }

    override fun verifyRegistrationNumber(selectedCountry: Country, phoneE164: String): AppError? {
        return when (val status = checkSimState()) {
            SimCheckResult.PermissionRequired -> AppError.SimPermissionRequired
            SimCheckResult.NoSim -> AppError.SimRequired
            is SimCheckResult.VerifiedWithNumbers -> {
                if (phoneE164 in status.numbersE164) null else AppError.SimNumberMismatch
            }
            is SimCheckResult.VerifiedCarrierOnly -> {
                // Active physical/eSIM is ready on device without stored MSISDN:
                // verify entered phone belongs to the active SIM's country.
                if (status.countryIsos.isNotEmpty() &&
                    !status.countryIsos.any { it.equals(selectedCountry.iso2, ignoreCase = true) }
                ) {
                    AppError.SimNumberMismatch
                } else {
                    null
                }
            }
        }
    }

    override fun parseCountryAndLocalNumber(e164: String): Pair<Country, String>? {
        return runCatching {
            val parsed = phoneUtil.parse(e164, null)
            val region = phoneUtil.getRegionCodeForNumber(parsed) ?: return@runCatching null
            val country = CountryCatalog.findByIso(region)
            val nationalDigits = parsed.nationalNumber.toString()
            val formattedNational = if (country.iso2.equals("BD", ignoreCase = true) && !nationalDigits.startsWith("0")) {
                "0$nationalDigits"
            } else {
                nationalDigits
            }
            country to formattedNational
        }.getOrNull()
    }

    private fun hasReadySimSlot(tm: TelephonyManager): Boolean {
        val defaultReady = runCatching { tm.simState == TelephonyManager.SIM_STATE_READY }
            .getOrDefault(false)
        if (defaultReady) return true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val count = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) tm.activeModemCount
                else @Suppress("DEPRECATION") tm.phoneCount
            }.getOrDefault(1).coerceIn(1, 4)

            for (slot in 0 until count) {
                val slotReady = runCatching {
                    tm.getSimState(slot) == TelephonyManager.SIM_STATE_READY
                }.getOrDefault(false)
                if (slotReady) return true
            }
        }
        return false
    }

    private fun normalizeSimCandidate(raw: String?, simCountryIso: String?): String? {
        val cleaned = raw?.trim().orEmpty()
        if (cleaned.length < 6) return null
        val country = if (!simCountryIso.isNullOrBlank() && simCountryIso.length == 2) {
            CountryCatalog.findByIso(simCountryIso)
        } else {
            CountryCatalog.findByIso("BD")
        }
        return CountryCatalog.normalizeToE164(country, cleaned)
    }
}
