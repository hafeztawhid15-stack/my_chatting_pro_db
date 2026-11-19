package com.mychattingpro.core.network

import android.content.Context
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.example.BuildConfig
import com.mychattingpro.core.datastore.AppPreferencesDataStore
import com.mychattingpro.core.security.EncryptedTokenStore
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit

class NetworkModule(
    context: Context,
    tokenStore: EncryptedTokenStore,
    prefsStore: AppPreferencesDataStore
) {
    val baseUrl: String = BuildConfig.CLOUDFLARE_API_BASE_URL
        .ifBlank { "https://my-chatting-pro-api.workers.dev" }
        .trimEnd('/') + "/"

    val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    // Never log Authorization headers or sensitive request/response bodies in production
    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
        redactHeader("Authorization")
        redactHeader("Cookie")
    }

    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(AuthInterceptor(tokenStore))
        .addInterceptor(loggingInterceptor)
        .authenticator(TokenAuthenticator(baseUrl, tokenStore, moshi))
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()

    val retrofit: Retrofit = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(okHttpClient)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()

    val apiService: CloudflareApiService = retrofit.create(CloudflareApiService::class.java)

    val networkMonitor = NetworkMonitor(context)

    val realtimeSocketManager = RealtimeSocketManager(
        baseUrl = baseUrl,
        okHttpClient = okHttpClient,
        tokenStore = tokenStore,
        prefsStore = prefsStore,
        networkMonitor = networkMonitor
    )

    val imageLoader: ImageLoader = ImageLoader.Builder(context)
        .okHttpClient(okHttpClient)
        .memoryCache {
            MemoryCache.Builder(context)
                .maxSizePercent(0.20)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(File(context.cacheDir, "image_cache"))
                .maxSizePercent(0.05)
                .build()
        }
        .crossfade(true)
        .build()

    fun mediaContentUrl(mediaId: String): String =
        "${baseUrl}v1/media/$mediaId/content"
}
