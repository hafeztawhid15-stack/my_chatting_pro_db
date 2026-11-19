package com.mychattingpro.core.common

import android.content.Context
import com.example.R

sealed class AppError {
    data object InvalidPhone : AppError()
    data object InvalidPin : AppError()
    data object PinMismatch : AppError()
    data object WrongPin : AppError()
    data object AccountNotFound : AppError()
    data object DuplicateAccount : AppError()
    data class RateLimited(val retryAfterSeconds: Int) : AppError()
    data object NetworkUnavailable : AppError()
    data object StorageFailure : AppError()
    data class MediaTooLarge(val maxMb: Int) : AppError()
    data object CallFailure : AppError()
    data object InvalidOwnerKey : AppError()
    data object OwnerAccessDenied : AppError()
    data object OwnerMonitorNotConfigured : AppError()
    data class ServerError(val serverMessage: String? = null) : AppError()

    fun toLocalizedMessage(context: Context): String = when (this) {
        InvalidPhone -> context.getString(R.string.err_invalid_phone)
        InvalidPin -> context.getString(R.string.err_invalid_pin)
        PinMismatch -> context.getString(R.string.err_pin_mismatch)
        WrongPin -> context.getString(R.string.err_wrong_pin)
        AccountNotFound -> context.getString(R.string.err_account_not_found)
        DuplicateAccount -> context.getString(R.string.err_duplicate_account)
        is RateLimited -> context.getString(R.string.err_rate_limit, retryAfterSeconds.coerceAtLeast(1))
        NetworkUnavailable -> context.getString(R.string.err_network)
        StorageFailure -> context.getString(R.string.err_storage)
        is MediaTooLarge -> context.getString(R.string.err_media_too_large, maxMb)
        CallFailure -> context.getString(R.string.err_call)
        InvalidOwnerKey -> context.getString(R.string.err_invalid_owner_key)
        OwnerAccessDenied -> context.getString(R.string.err_owner_access_denied)
        OwnerMonitorNotConfigured -> context.getString(R.string.err_owner_not_configured)
        is ServerError -> serverMessage?.takeIf { it.isNotBlank() } ?: context.getString(R.string.err_server)
    }
}
