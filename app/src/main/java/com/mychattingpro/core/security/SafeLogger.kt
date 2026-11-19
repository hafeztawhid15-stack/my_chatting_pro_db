package com.mychattingpro.core.security

import android.util.Log
import com.example.BuildConfig

/**
 * Structured logger that is completely silent in release builds and never accepts
 * sensitive payloads (PINs, tokens, private message bodies, or signed media URLs).
 */
object SafeLogger {
    private const val TAG = "MyChattingPro"

    fun d(component: String, message: String) {
        if (BuildConfig.DEBUG) {
            Log.d("$TAG:$component", message)
        }
    }

    fun w(component: String, message: String) {
        if (BuildConfig.DEBUG) {
            Log.w("$TAG:$component", message)
        }
    }

    fun e(component: String, message: String, errorType: String? = null) {
        if (BuildConfig.DEBUG) {
            val suffix = if (errorType != null) " [type=$errorType]" else ""
            Log.e("$TAG:$component", "$message$suffix")
        }
    }
}
