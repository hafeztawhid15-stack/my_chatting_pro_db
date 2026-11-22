package com.mychattingpro.features.settings

import com.example.BuildConfig
import com.mychattingpro.domain.models.AppUpdateInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class AppUpdateChecker(private val okHttpClient: OkHttpClient) {

    suspend fun checkForUpdate(): Result<AppUpdateInfo> = withContext(Dispatchers.IO) {
        val currentVersion = BuildConfig.VERSION_NAME
        val currentCode = BuildConfig.VERSION_CODE
        val storeUrl = "https://play.google.com/store/apps/details?id=${BuildConfig.APPLICATION_ID}&hl=en"

        runCatching {
            val request = Request.Builder()
                .url(storeUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@runCatching AppUpdateInfo(
                        currentVersionName = currentVersion,
                        currentVersionCode = currentCode,
                        storeVersionName = null,
                        isUpdateAvailable = false,
                        checkedOnline = false,
                        storeUrl = storeUrl
                    )
                }
                val html = response.body?.string().orEmpty()
                val versionRegex = Regex("""\[\[\["([0-9]+\.[0-9]+(?:\.[0-9]+)*)"]]""")
                val storeVer = versionRegex.find(html)?.groupValues?.getOrNull(1)
                val hasUpdate = if (storeVer != null) {
                    compareSemver(storeVer, currentVersion) > 0
                } else {
                    false
                }
                AppUpdateInfo(
                    currentVersionName = currentVersion,
                    currentVersionCode = currentCode,
                    storeVersionName = storeVer,
                    isUpdateAvailable = hasUpdate,
                    checkedOnline = true,
                    storeUrl = storeUrl
                )
            }
        }
    }

    private fun compareSemver(a: String, b: String): Int {
        val pa = a.split('.').map { it.toIntOrNull() ?: 0 }
        val pb = b.split('.').map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(pa.size, pb.size)
        for (i in 0 until maxLen) {
            val va = pa.getOrElse(i) { 0 }
            val vb = pb.getOrElse(i) { 0 }
            if (va != vb) return va.compareTo(vb)
        }
        return 0
    }
}
