package com.mychattingpro.core.common

import kotlin.math.ceil

sealed interface SearchThrottleResult {
    data object Allowed : SearchThrottleResult
    data object DuplicateTooSoon : SearchThrottleResult
    data class RateLimited(val retryAfterSeconds: Int) : SearchThrottleResult
}

/**
 * Client-side sliding-window search rate limiter & enumeration abuse guard.
 * Complements the Cloudflare Worker `user_search` rate limit without weakening backend controls.
 */
class SearchRateLimiter(
    private val maxSearchesPerWindow: Int = 15,
    private val windowMs: Long = 60_000L,
    private val duplicateCooldownMs: Long = 2_000L,
    private val blockDurationMs: Long = 30_000L,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private val timestamps = ArrayDeque<Long>()
    private var blockedUntilMs: Long = 0L
    private var lastQueryKey: String? = null
    private var lastQueryAtMs: Long = 0L

    @Synchronized
    fun checkAndRecord(queryKey: String, bypassDuplicateCheck: Boolean = false): SearchThrottleResult {
        val now = clock()
        if (blockedUntilMs > now) {
            val remainingSec = ceil((blockedUntilMs - now) / 1000.0).toInt().coerceAtLeast(1)
            return SearchThrottleResult.RateLimited(remainingSec)
        }

        if (!bypassDuplicateCheck && queryKey == lastQueryKey && (now - lastQueryAtMs) < duplicateCooldownMs) {
            return SearchThrottleResult.DuplicateTooSoon
        }

        while (timestamps.isNotEmpty() && (now - timestamps.first()) >= windowMs) {
            timestamps.removeFirst()
        }

        if (timestamps.size >= maxSearchesPerWindow) {
            blockedUntilMs = now + blockDurationMs
            val remainingSec = ceil(blockDurationMs / 1000.0).toInt().coerceAtLeast(1)
            return SearchThrottleResult.RateLimited(remainingSec)
        }

        timestamps.addLast(now)
        lastQueryKey = queryKey
        lastQueryAtMs = now
        return SearchThrottleResult.Allowed
    }

    @Synchronized
    fun recordServerRateLimit(retryAfterSeconds: Int) {
        val now = clock()
        val target = now + retryAfterSeconds.coerceAtLeast(1) * 1000L
        if (target > blockedUntilMs) {
            blockedUntilMs = target
        }
    }

    @Synchronized
    fun remainingBlockSeconds(): Int {
        val now = clock()
        if (blockedUntilMs <= now) return 0
        return ceil((blockedUntilMs - now) / 1000.0).toInt().coerceAtLeast(1)
    }
}
