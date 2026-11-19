package com.mychattingpro.core.localization

import java.util.Locale

enum class AppLanguage(
    val code: String,
    val nativeLabel: String,
    val isRtl: Boolean
) {
    BENGALI("bn", "বাংলা", false),
    ENGLISH("en", "English", false),
    ARABIC("ar", "العربية", true);

    fun toLocale(): Locale = when (this) {
        BENGALI -> Locale.forLanguageTag("bn-BD")
        ENGLISH -> Locale.ENGLISH
        ARABIC -> Locale.forLanguageTag("ar")
    }

    companion object {
        fun fromCode(code: String?): AppLanguage =
            entries.firstOrNull { it.code == code } ?: ENGLISH
    }
}
