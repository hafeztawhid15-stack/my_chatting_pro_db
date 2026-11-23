package com.mychattingpro.features.ads

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.example.BuildConfig
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.mychattingpro.core.security.SafeLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/**
 * Manages full-screen Google AdMob Interstitial ads ONLY.
 * Policy compliance:
 * - Debug builds automatically use Google's official Test Interstitial Ad Unit ID.
 * - Release builds use the configured production Ad Unit ID.
 * - Minimum cooldown prevents rapid repeated full-screen interruptions.
 * - Strictly triggered ONLY when:
 *   1) A button/option in Settings is clicked
 *   2) A call ends / hangs up
 */
class InterstitialAdManager(private val appContext: Context) {

    companion object {
        const val PRODUCTION_INTERSTITIAL_AD_UNIT_ID = "ca-app-pub-9716395280477689/4017609898"
        const val TEST_INTERSTITIAL_AD_UNIT_ID = "ca-app-pub-3940256099942544/1033173712"
        const val DEFAULT_COOLDOWN_MS = 20_000L
        const val MAX_AD_AGE_MS = 50L * 60L * 1000L
        private const val INIT_TIMEOUT_MS = 12_000L
        private const val LOAD_TIMEOUT_MS = 25_000L
        private const val MAX_BACKGROUND_RETRIES = 12
        private const val TAG = "InterstitialAdManager"

        fun resolveAdUnitId(isDebug: Boolean = BuildConfig.DEBUG): String =
            if (isDebug) TEST_INTERSTITIAL_AD_UNIT_ID else PRODUCTION_INTERSTITIAL_AD_UNIT_ID

        fun isCooldownActive(
            nowMs: Long,
            lastShownAtMs: Long,
            cooldownMs: Long = DEFAULT_COOLDOWN_MS
        ): Boolean = lastShownAtMs > 0L && (nowMs - lastShownAtMs) in 0L until cooldownMs

        fun retryDelayMs(attempt: Int): Long = when {
            attempt <= 0 -> 5_000L
            attempt == 1 -> 15_000L
            attempt == 2 -> 30_000L
            attempt == 3 -> 60_000L
            else -> 120_000L
        }

        fun isLoadedAdExpired(
            nowMs: Long,
            loadedAtMs: Long,
            maxAgeMs: Long = MAX_AD_AGE_MS
        ): Boolean = loadedAtMs > 0L && (nowMs - loadedAtMs) >= maxAgeMs
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val initScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var initialized = false

    @Volatile
    private var isInitializing = false

    @Volatile
    private var pendingPreloadAfterInit = false

    @Volatile
    private var isLoading = false

    @Volatile
    private var loadedAd: InterstitialAd? = null

    @Volatile
    private var loadedAtMs: Long = 0L

    @Volatile
    private var lastShownAtMs: Long = 0L

    @Volatile
    private var lifecycleAttached = false

    @Volatile
    private var currentActivityRef: WeakReference<Activity>? = null

    private var retryAttempt = 0
    private var pendingRetryRunnable: Runnable? = null
    private var initWatchdogRunnable: Runnable? = null
    private var loadWatchdogRunnable: Runnable? = null

    fun attachApplication(application: Application) {
        if (lifecycleAttached) return
        lifecycleAttached = true
        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                    updateActiveActivity(activity)
                }

                override fun onActivityStarted(activity: Activity) {
                    updateActiveActivity(activity)
                    preload(resetRetry = false)
                }

                override fun onActivityResumed(activity: Activity) {
                    updateActiveActivity(activity)
                    preload(resetRetry = false)
                }

                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

                override fun onActivityDestroyed(activity: Activity) {
                    if (currentActivityRef?.get() === activity) {
                        currentActivityRef = null
                    }
                }
            }
        )
    }

    private fun updateActiveActivity(activity: Activity) {
        if (!activity.isFinishing && !activity.isDestroyed) {
            currentActivityRef = WeakReference(activity)
        }
    }

    private fun resolveActiveActivity(explicitActivity: Activity?): Activity? {
        explicitActivity?.takeUnless { it.isFinishing || it.isDestroyed }?.let { return it }
        return currentActivityRef?.get()?.takeUnless { it.isFinishing || it.isDestroyed }
    }

    fun initialize() {
        mainHandler.post {
            if (initialized) {
                preloadInternal()
                return@post
            }
            if (isInitializing) {
                pendingPreloadAfterInit = true
                return@post
            }
            isInitializing = true
            pendingPreloadAfterInit = true

            initWatchdogRunnable?.let { mainHandler.removeCallbacks(it) }
            val watchdog = Runnable {
                initWatchdogRunnable = null
                if (!initialized && isInitializing) {
                    isInitializing = false
                    SafeLogger.w(TAG, "MobileAds init timed out; resetting state")
                    scheduleRetry()
                }
            }
            initWatchdogRunnable = watchdog
            mainHandler.postDelayed(watchdog, INIT_TIMEOUT_MS)

            initScope.launch {
                runCatching {
                    if (BuildConfig.DEBUG) {
                        MobileAds.setRequestConfiguration(
                            RequestConfiguration.Builder()
                                .setTestDeviceIds(listOf(AdRequest.DEVICE_ID_EMULATOR))
                                .build()
                        )
                    }
                    MobileAds.initialize(appContext) {
                        mainHandler.post {
                            initWatchdogRunnable?.let { mainHandler.removeCallbacks(it) }
                            initWatchdogRunnable = null
                            initialized = true
                            isInitializing = false
                            SafeLogger.d(TAG, "MobileAds initialized (debug=${BuildConfig.DEBUG})")
                            if (pendingPreloadAfterInit) {
                                pendingPreloadAfterInit = false
                                preloadInternal()
                            }
                        }
                    }
                }.onFailure { error ->
                    mainHandler.post {
                        initWatchdogRunnable?.let { mainHandler.removeCallbacks(it) }
                        initWatchdogRunnable = null
                        isInitializing = false
                        SafeLogger.w(TAG, "MobileAds init failed: ${error.message}")
                        scheduleRetry()
                    }
                }
            }
        }
    }

    fun preload(resetRetry: Boolean = false) {
        mainHandler.post {
            if (resetRetry) {
                retryAttempt = 0
            }
            preloadInternal()
        }
    }

    private fun preloadInternal() {
        if (!initialized) {
            pendingPreloadAfterInit = true
            if (!isInitializing) {
                initialize()
            }
            return
        }

        val nowMs = System.currentTimeMillis()
        if (loadedAd != null) {
            if (isLoadedAdExpired(nowMs, loadedAtMs)) {
                loadedAd = null
                loadedAtMs = 0L
            } else {
                return
            }
        }
        if (isLoading) return

        pendingRetryRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingRetryRunnable = null
        loadWatchdogRunnable?.let { mainHandler.removeCallbacks(it) }

        isLoading = true
        val loadWatchdog = Runnable {
            loadWatchdogRunnable = null
            if (isLoading) {
                isLoading = false
                SafeLogger.w(TAG, "Interstitial load timed out; scheduling retry")
                scheduleRetry()
            }
        }
        loadWatchdogRunnable = loadWatchdog
        mainHandler.postDelayed(loadWatchdog, LOAD_TIMEOUT_MS)

        runCatching {
            val adUnitId = resolveAdUnitId(BuildConfig.DEBUG)
            val request = AdRequest.Builder().build()
            InterstitialAd.load(
                appContext,
                adUnitId,
                request,
                object : InterstitialAdLoadCallback() {
                    override fun onAdLoaded(ad: InterstitialAd) {
                        loadWatchdogRunnable?.let { mainHandler.removeCallbacks(it) }
                        loadWatchdogRunnable = null
                        isLoading = false
                        retryAttempt = 0
                        loadedAd = ad
                        loadedAtMs = System.currentTimeMillis()
                        SafeLogger.d(TAG, "Interstitial ad loaded and ready")
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        loadWatchdogRunnable?.let { mainHandler.removeCallbacks(it) }
                        loadWatchdogRunnable = null
                        isLoading = false
                        loadedAd = null
                        loadedAtMs = 0L
                        SafeLogger.w(TAG, "Interstitial load failed code=${error.code} domain=${error.domain}")
                        scheduleRetry()
                    }
                }
            )
        }.onFailure { error ->
            loadWatchdogRunnable?.let { mainHandler.removeCallbacks(it) }
            loadWatchdogRunnable = null
            isLoading = false
            SafeLogger.w(TAG, "Interstitial load threw: ${error.message}")
            scheduleRetry()
        }
    }

    private fun scheduleRetry() {
        if (retryAttempt >= MAX_BACKGROUND_RETRIES) return
        val delayMs = retryDelayMs(retryAttempt)
        retryAttempt++
        pendingRetryRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            pendingRetryRunnable = null
            if (loadedAd == null && !isLoading) {
                preloadInternal()
            }
        }
        pendingRetryRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    /**
     * Shows a loaded full-screen Interstitial ad if available and outside cooldown,
     * then runs [onContinue] when dismissed or skipped (so UX is never blocked).
     */
    fun showThen(activity: Activity?, onContinue: () -> Unit) {
        mainHandler.post {
            val nowMs = System.currentTimeMillis()
            if (loadedAd != null && isLoadedAdExpired(nowMs, loadedAtMs)) {
                loadedAd = null
                loadedAtMs = 0L
            }
            val ad = loadedAd
            val hostActivity = resolveActiveActivity(activity)
            if (
                ad == null ||
                hostActivity == null ||
                isCooldownActive(nowMs, lastShownAtMs)
            ) {
                preload(resetRetry = true)
                onContinue()
                return@post
            }

            loadedAd = null
            loadedAtMs = 0L
            val previousShownAtMs = lastShownAtMs
            lastShownAtMs = nowMs
            var continued = false
            fun finishOnce() {
                if (!continued) {
                    continued = true
                    preload(resetRetry = true)
                    onContinue()
                }
            }

            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdShowedFullScreenContent() {
                    lastShownAtMs = System.currentTimeMillis()
                }

                override fun onAdDismissedFullScreenContent() {
                    finishOnce()
                }

                override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                    SafeLogger.w(TAG, "Interstitial failed to show code=${adError.code}")
                    lastShownAtMs = previousShownAtMs
                    finishOnce()
                }
            }

            runCatching {
                ad.show(hostActivity)
            }.onFailure { error ->
                SafeLogger.w(TAG, "Interstitial show threw: ${error.javaClass.simpleName}")
                lastShownAtMs = previousShownAtMs
                finishOnce()
            }
        }
    }
}

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

