package com.mychattingpro.data.repository

import com.mychattingpro.core.common.AppError
import com.mychattingpro.core.network.ApiErrorResponseDto
import com.squareup.moshi.Moshi
import retrofit2.HttpException
import java.io.FileNotFoundException
import java.io.IOException
import java.net.SocketTimeoutException

class AppException(val appError: AppError) : Exception()

class ErrorMapper(private val moshi: Moshi) {
    private val adapter = moshi.adapter(ApiErrorResponseDto::class.java)

    fun map(throwable: Throwable): AppError {
        if (throwable is AppException) return throwable.appError
        if (throwable is SocketTimeoutException) return AppError.TimeoutError
        if (throwable is FileNotFoundException) return AppError.StorageFailure
        if (throwable is IOException) return AppError.NetworkUnavailable
        if (throwable is HttpException) {
            val body = runCatching { throwable.response()?.errorBody()?.string() }.getOrNull()
            val parsed = body?.let { runCatching { adapter.fromJson(it) }.getOrNull() }
            val path = throwable.response()?.raw()?.request?.url?.encodedPath.orEmpty()
            return when (parsed?.error) {
                "invalid_phone" -> AppError.InvalidPhone
                "invalid_pin", "pin_reuse_not_allowed" -> AppError.InvalidPin
                "pin_mismatch" -> AppError.PinMismatch
                "wrong_pin" -> AppError.WrongPin
                "account_not_found" -> AppError.AccountNotFound
                "duplicate_account" -> AppError.DuplicateAccount
                "account_locked", "rate_limited" -> AppError.RateLimited(parsed.retryAfterSeconds ?: 60)
                "unsupported_media_type" -> AppError.UnsupportedMediaType
                "empty_media_file" -> AppError.EmptyMediaFile
                "media_too_large" -> AppError.MediaTooLarge(50)
                "storage_permission_denied" -> AppError.StoragePermissionDenied
                "storage_bucket_missing" -> AppError.StorageBucketMissing
                "invalid_media", "media_not_found", "media_object_missing",
                "invalid_media_token", "storage_upload_failed", "storage_download_failed" -> AppError.StorageFailure
                "invalid_owner_key" -> AppError.InvalidOwnerKey
                "owner_access_denied" -> AppError.OwnerAccessDenied
                "owner_monitor_not_configured" -> AppError.OwnerMonitorNotConfigured
                "wrong_private_pin" -> AppError.WrongPrivatePin
                "private_pin_locked" -> AppError.PrivatePinLocked(parsed.retryAfterSeconds ?: 60)
                "private_pin_not_set" -> AppError.PrivatePinNotSet
                "private_pin_already_set" -> AppError.PrivatePinAlreadySet
                "private_chat_locked" -> AppError.PrivateChatLocked
                "invalid_recovery_code" -> AppError.InvalidRecoveryCode
                "recovery_code_not_set" -> AppError.RecoveryCodeNotSet
                "invalid_delete_confirmation" -> AppError.InvalidDeleteConfirmation
                "duplicate_report" -> AppError.DuplicateReport
                "session_revoked", "session_compromised", "invalid_refresh_token" -> AppError.SessionRevoked
                "user_blocked" -> AppError.UserBlocked
                else -> when (throwable.code()) {
                    401 -> when {
                        path.contains("/auth/login") || path.contains("/pin/change") ||
                            path.contains("/account/delete") || path.contains("/recovery-code") -> AppError.WrongPin
                        else -> AppError.SessionRevoked
                    }
                    403 -> AppError.ServerError(parsed?.message)
                    404 -> when {
                        path.contains("/auth/") || path.contains("/users/search") -> AppError.AccountNotFound
                        else -> AppError.ServerError(parsed?.message)
                    }
                    409 -> AppError.ServerError(parsed?.message)
                    413 -> AppError.MediaTooLarge(50)
                    415 -> AppError.UnsupportedMediaType
                    429 -> AppError.RateLimited(parsed?.retryAfterSeconds ?: 60)
                    else -> AppError.ServerError(parsed?.message)
                }
            }
        }
        return AppError.ServerError()
    }
}
