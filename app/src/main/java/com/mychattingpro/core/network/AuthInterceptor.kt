package com.mychattingpro.core.network

import com.mychattingpro.core.security.EncryptedTokenStore
import com.squareup.moshi.Moshi
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route

class AuthInterceptor(
    private val tokenStore: EncryptedTokenStore
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val path = original.url.encodedPath
        if (path.endsWith("/v1/auth/login") ||
            path.endsWith("/v1/auth/register") ||
            path.endsWith("/v1/auth/refresh")
        ) {
            return chain.proceed(original)
        }

        val token = runBlocking { tokenStore.getSession()?.accessToken }
        val request = if (!token.isNullOrBlank()) {
            original.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        } else {
            original
        }
        return chain.proceed(request)
    }
}

class TokenAuthenticator(
    private val baseUrl: String,
    private val tokenStore: EncryptedTokenStore,
    private val moshi: Moshi
) : Authenticator {

    private val lock = Any()
    private val refreshClient = OkHttpClient.Builder().build()

    override fun authenticate(route: Route?, response: Response): Request? {
        if (responseCount(response) >= 2) return null
        val path = response.request.url.encodedPath
        if (path.endsWith("/v1/auth/refresh") || path.endsWith("/v1/auth/login")) return null

        synchronized(lock) {
            val currentSession = runBlocking { tokenStore.getSession() } ?: return null
            val requestAuth = response.request.header("Authorization")?.removePrefix("Bearer ")?.trim()

            // Another thread already refreshed the token
            if (requestAuth != null && requestAuth != currentSession.accessToken) {
                return response.request.newBuilder()
                    .header("Authorization", "Bearer ${currentSession.accessToken}")
                    .build()
            }

            val reqAdapter = moshi.adapter(RefreshRequestDto::class.java)
            val respAdapter = moshi.adapter(RefreshResponseDto::class.java)
            val bodyJson = reqAdapter.toJson(RefreshRequestDto(currentSession.refreshToken))
            val refreshReq = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/v1/auth/refresh")
                .post(bodyJson.toRequestBody("application/json".toMediaType()))
                .build()

            return try {
                refreshClient.newCall(refreshReq).execute().use { refreshResp ->
                    if (!refreshResp.isSuccessful) {
                        if (refreshResp.code == 401) {
                            runBlocking { tokenStore.clearSession() }
                        }
                        return null
                    }
                    val parsed = refreshResp.body?.string()?.let { respAdapter.fromJson(it) } ?: return null
                    runBlocking { tokenStore.updateTokens(parsed.accessToken, parsed.refreshToken) }
                    response.request.newBuilder()
                        .header("Authorization", "Bearer ${parsed.accessToken}")
                        .build()
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }
}
