package com.mychattingpro.data.repository

import com.mychattingpro.core.common.AppError
import com.mychattingpro.core.network.ApiErrorResponseDto
import com.squareup.moshi.Moshi
import retrofit2.HttpException
import java.io.IOException

class AppException(val appError: AppError) : Exception()

class ErrorMapper(private val moshi: Moshi) {
    private val adapter = moshi.adapter(ApiErrorResponseDto::class.java)

    fun map(throwable: Throwable): AppError {
        if (throwable is AppException) return throwable.appError
        if (throwable is IOException) return AppError.NetworkUnavailable
        if (throwable is HttpException) {
            val body = runCatching { throwable.response()?.errorBody()?.string() }.getOrNull()
            val parsed = body?.let { runCatching { adapter.fromJson(it) }.getOrNull() }
            return when (parsed?.error) {
                "invalid_phone" -> AppError.InvalidPhone
                "invalid_pin" -> AppError.InvalidPin
                "pin_mismatch" -> AppError.PinMismatch
                "wrong_pin" -> AppError.WrongPin
                "account_not_found" -> AppError.AccountNotFound
                "duplicate_account" -> AppError.DuplicateAccount
                "account_locked", "rate_limited" -> AppError.RateLimited(parsed.retryAfterSeconds ?: 60)
                "invalid_media", "media_not_found", "media_object_missing", "invalid_media_token" -> AppError.StorageFailure
                "invalid_owner_key" -> AppError.InvalidOwnerKey
                "owner_access_denied" -> AppError.OwnerAccessDenied
                "owner_monitor_not_configured" -> AppError.OwnerMonitorNotConfigured
                else -> when (throwable.code()) {
                    401 -> AppError.WrongPin
                    403 -> AppError.ServerError(parsed?.message)
                    404 -> AppError.AccountNotFound
                    409 -> AppError.DuplicateAccount
                    429 -> AppError.RateLimited(parsed?.retryAfterSeconds ?: 60)
                    else -> AppError.ServerError(parsed?.message)
                }
            }
        }
        return AppError.ServerError()
    }
}
