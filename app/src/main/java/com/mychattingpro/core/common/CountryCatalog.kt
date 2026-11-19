package com.mychattingpro.core.common

import com.google.i18n.phonenumbers.PhoneNumberUtil
import java.util.Locale

data class Country(
    val iso2: String,
    val name: String,
    val callingCode: String,
    val flagEmoji: String
)

/**
 * Complete international country catalog derived from ISO-3166 regions and Google's
 * libphonenumber metadata — never hardcoded to a single country.
 */
object CountryCatalog {

    private val phoneUtil: PhoneNumberUtil = PhoneNumberUtil.getInstance()

    val popularIsoCodes: List<String> = listOf("BD", "US", "GB", "SA", "AE", "IN", "MY", "CA", "DE", "AU")

    val allCountries: List<Country> by lazy {
        val supported = phoneUtil.supportedRegions
        supported.mapNotNull { iso ->
            val dialInt = phoneUtil.getCountryCodeForRegion(iso)
            if (dialInt <= 0) return@mapNotNull null
            val locale = Locale("", iso)
            val displayName = locale.getDisplayCountry(Locale.ENGLISH).ifBlank { iso }
            Country(
                iso2 = iso,
                name = displayName,
                callingCode = "+$dialInt",
                flagEmoji = isoToFlagEmoji(iso)
            )
        }.sortedBy { it.name }
    }

    fun findByIso(iso2: String): Country =
        allCountries.firstOrNull { it.iso2.equals(iso2, ignoreCase = true) }
            ?: allCountries.firstOrNull { it.iso2 == "BD" }
            ?: Country("US", "United States", "+1", "🇺🇸")

    fun normalizeToE164(country: Country, nationalInput: String): String? {
        val trimmed = nationalInput.trim()
        if (trimmed.isEmpty()) return null
        return try {
            val parsed = if (trimmed.startsWith("+")) {
                phoneUtil.parse(trimmed, null)
            } else {
                phoneUtil.parse(trimmed, country.iso2)
            }
            if (!phoneUtil.isPossibleNumber(parsed)) return null
            if (!phoneUtil.isValidNumber(parsed)) {
                // Allow valid-length numbers for regions with newer allocations when possible
                val digitsOnly = trimmed.filter { it.isDigit() }
                if (digitsOnly.length < 6) return null
            }
            phoneUtil.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164)
        } catch (_: Exception) {
            null
        }
    }

    private fun isoToFlagEmoji(iso2: String): String {
        if (iso2.length != 2) return "🌐"
        val upper = iso2.uppercase(Locale.US)
        val first = Character.codePointAt(upper, 0) - 0x41 + 0x1F1E6
        val second = Character.codePointAt(upper, 1) - 0x41 + 0x1F1E6
        return String(Character.toChars(first)) + String(Character.toChars(second))
    }
}
