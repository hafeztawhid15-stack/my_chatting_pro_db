package com.mychattingpro.features.ads

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
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
        private const val TAG = "InterstitialAdManager"

        fun resolveAdUnitId(isDebug: Boolean = BuildConfig.DEBUG): String =
            if (isDebug) TEST_INTERSTITIAL_AD_UNIT_ID else PRODUCTION_INTERSTITIAL_AD_UNIT_ID

        fun isCooldownActive(
            nowMs: Long,
            lastShownAtMs: Long,
            cooldownMs: Long = DEFAULT_COOLDOWN_MS
        ): Boolean = lastShownAtMs > 0L && (nowMs - lastShownAtMs) in 0L until cooldownMs
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val initScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var initialized = false

    @Volatile
    private var isLoading = false

    @Volatile
    private var loadedAd: InterstitialAd? = null

    @Volatile
    private var lastShownAtMs: Long = 0L

    fun initialize() {
        if (initialized) return
        initialized = true
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
                    SafeLogger.d(TAG, "MobileAds initialized (debug=${BuildConfig.DEBUG})")
                    preload()
                }
            }.onFailure { error ->
                SafeLogger.w(TAG, "MobileAds init skipped: ${error.message}")
            }
        }
    }

    fun preload() {
        mainHandler.post {
            if (loadedAd != null || isLoading) return@post
            isLoading = true
            runCatching {
                val adUnitId = resolveAdUnitId(BuildConfig.DEBUG)
                val request = AdRequest.Builder().build()
                InterstitialAd.load(
                    appContext,
                    adUnitId,
                    request,
                    object : InterstitialAdLoadCallback() {
                        override fun onAdLoaded(ad: InterstitialAd) {
                            isLoading = false
                            loadedAd = ad
                            SafeLogger.d(TAG, "Interstitial ad loaded and ready")
                        }

                        override fun onAdFailedToLoad(error: LoadAdError) {
                            isLoading = false
                            loadedAd = null
                            SafeLogger.w(TAG, "Interstitial load failed code=${error.code}")
                        }
                    }
                )
            }.onFailure { error ->
                isLoading = false
                SafeLogger.w(TAG, "Interstitial load skipped: ${error.message}")
            }
        }
    }

    /**
     * Shows a loaded full-screen Interstitial ad if available and outside cooldown,
     * then runs [onContinue] when dismissed or skipped (so UX is never blocked).
     */
    fun showThen(activity: Activity?, onContinue: () -> Unit) {
        mainHandler.post {
            val nowMs = System.currentTimeMillis()
            val ad = loadedAd
            if (
                ad == null ||
                activity == null ||
                activity.isFinishing ||
                activity.isDestroyed ||
                isCooldownActive(nowMs, lastShownAtMs)
            ) {
                preload()
                onContinue()
                return@post
            }

            loadedAd = null
            lastShownAtMs = nowMs
            var continued = false
            fun finishOnce() {
                if (!continued) {
                    continued = true
                    preload()
                    onContinue()
                }
            }

            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    finishOnce()
                }

                override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                    SafeLogger.w(TAG, "Interstitial failed to show code=${adError.code}")
                    finishOnce()
                }
            }

            ad.show(activity)
        }
    }
}

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
