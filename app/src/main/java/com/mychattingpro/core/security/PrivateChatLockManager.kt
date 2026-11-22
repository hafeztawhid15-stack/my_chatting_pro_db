package com.mychattingpro.core.security

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Strictly in-memory lock manager for the separate Private Chat section.
 * - Never persists unlock tokens or Private PINs to disk/DataStore.
 * - Auto-locks on app background, server lock/403 response, logout/account deletion,
 *   token expiration, or inactivity timeout.
 */
class PrivateChatLockManager(
    private val inactivityTimeoutMs: Long = DEFAULT_INACTIVITY_TIMEOUT_MS,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    companion object {
        const val DEFAULT_INACTIVITY_TIMEOUT_MS = 120_000L
    }

    private val lockMonitor = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var unlockToken: String? = null

    @Volatile
    private var tokenExpiresAtMs: Long = 0L

    @Volatile
    private var lastInteractionMs: Long = 0L

    private var expiryWatcherJob: Job? = null

    private val _isUnlocked = MutableStateFlow(false)
    val isUnlocked: StateFlow<Boolean> = _isUnlocked.asStateFlow()

    fun unlock(token: String, expiresInSeconds: Int) {
        if (token.isBlank()) return
        val now = clock()
        synchronized(lockMonitor) {
            unlockToken = token
            tokenExpiresAtMs = now + (expiresInSeconds.coerceAtLeast(10) * 1000L)
            lastInteractionMs = now
            _isUnlocked.value = true
            startWatcherLocked()
        }
    }

    fun touchActivity() {
        val now = clock()
        synchronized(lockMonitor) {
            if (unlockToken == null) return
            if (isExpiredLocked(now)) {
                lockInternalLocked()
            } else {
                lastInteractionMs = now
            }
        }
    }

    fun currentTokenOrNull(): String? {
        val now = clock()
        synchronized(lockMonitor) {
            val token = unlockToken ?: return null
            if (isExpiredLocked(now)) {
                lockInternalLocked()
                return null
            }
            return token
        }
    }

    fun lock() {
        synchronized(lockMonitor) {
            lockInternalLocked()
        }
    }

    private fun isExpiredLocked(now: Long): Boolean {
        val serverExpired = now >= tokenExpiresAtMs
        val idleExpired = (now - lastInteractionMs) >= inactivityTimeoutMs
        return serverExpired || idleExpired
    }

    private fun startWatcherLocked() {
        expiryWatcherJob?.cancel()
        val pollIntervalMs = (inactivityTimeoutMs / 4L).coerceIn(250L, 5_000L)
        expiryWatcherJob = scope.launch {
            while (isActive) {
                delay(pollIntervalMs)
                synchronized(lockMonitor) {
                    if (unlockToken == null) {
                        expiryWatcherJob?.cancel()
                        return@launch
                    }
                    if (isExpiredLocked(clock())) {
                        lockInternalLocked()
                        return@launch
                    }
                }
            }
        }
    }

    private fun lockInternalLocked() {
        unlockToken = null
        tokenExpiresAtMs = 0L
        lastInteractionMs = 0L
        expiryWatcherJob?.cancel()
        expiryWatcherJob = null
        _isUnlocked.value = false
    }
}
