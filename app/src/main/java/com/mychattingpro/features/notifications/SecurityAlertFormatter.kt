package com.mychattingpro.features.notifications

import android.content.Context
import com.example.R
import com.mychattingpro.core.localization.AppLanguage
import com.mychattingpro.core.localization.DateTimeFormatters
import com.mychattingpro.domain.models.SecurityAlertItem

data class FormattedSecurityAlert(
    val title: String,
    val body: String,
    val isUrgentLoginAlert: Boolean
)

object SecurityAlertFormatter {

    fun format(
        context: Context,
        alert: SecurityAlertItem,
        language: AppLanguage
    ): FormattedSecurityAlert = formatRaw(
        context = context,
        alertType = alert.alertType,
        deviceName = alert.deviceName,
        atMs = alert.createdAtMs,
        language = language
    )

    fun formatRaw(
        context: Context,
        alertType: String,
        deviceName: String?,
        atMs: Long,
        language: AppLanguage
    ): FormattedSecurityAlert {
        val localizedCtx = context.createConfigurationContext(
            android.content.res.Configuration(context.resources.configuration).apply {
                setLocale(language.toLocale())
            }
        )
        return when (alertType) {
            "new_login" -> {
                val title = localizedCtx.getString(R.string.security_new_login_title)
                val body = if (!deviceName.isNullOrBlank()) {
                    val timeLabel = DateTimeFormatters.formatFullDateTime(localizedCtx, atMs, language)
                    localizedCtx.getString(R.string.security_new_login_body, deviceName, timeLabel)
                } else {
                    localizedCtx.getString(R.string.security_new_login_body_simple)
                }
                FormattedSecurityAlert(title = title, body = body, isUrgentLoginAlert = true)
            }
            "suspicious_login_attempt" -> FormattedSecurityAlert(
                title = localizedCtx.getString(R.string.security_suspicious_title),
                body = localizedCtx.getString(R.string.security_suspicious_body),
                isUrgentLoginAlert = true
            )
            "login_pin_changed" -> FormattedSecurityAlert(
                title = localizedCtx.getString(R.string.security_pin_changed_title),
                body = localizedCtx.getString(R.string.security_pin_changed_body),
                isUrgentLoginAlert = false
            )
            "login_pin_reset" -> FormattedSecurityAlert(
                title = localizedCtx.getString(R.string.security_pin_reset_title),
                body = localizedCtx.getString(
                    R.string.security_pin_reset_body,
                    deviceName?.takeIf { it.isNotBlank() } ?: "Android Device"
                ),
                isUrgentLoginAlert = true
            )
            "session_revoked", "logout_all_devices" -> FormattedSecurityAlert(
                title = localizedCtx.getString(R.string.security_session_revoked_title),
                body = localizedCtx.getString(R.string.security_session_revoked_body),
                isUrgentLoginAlert = false
            )
            else -> FormattedSecurityAlert(
                title = localizedCtx.getString(R.string.security_generic_title),
                body = localizedCtx.getString(R.string.security_generic_body),
                isUrgentLoginAlert = false
            )
        }
    }
}
