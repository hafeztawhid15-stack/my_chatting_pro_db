package com.mychattingpro.core.network

import android.content.Context
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.example.BuildConfig
import com.mychattingpro.core.datastore.AppPreferencesDataStore
import com.mychattingpro.core.security.EncryptedTokenStore
import com.mychattingpro.core.security.PrivateChatLockManager
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit

class NetworkModule(
    context: Context,
    tokenStore: EncryptedTokenStore,
    prefsStore: AppPreferencesDataStore,
    privateChatLockManager: PrivateChatLockManager = PrivateChatLockManager()
) {
    val baseUrl: String = BuildConfig.CLOUDFLARE_API_BASE_URL
        .ifBlank { "https://my-chatting-pro-api.workers.dev" }
        .trimEnd('/') + "/"

    val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    // Never log Authorization, X-Private-Unlock-Token, or X-Owner-Key headers
    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
        redactHeader("Authorization")
        redactHeader("Cookie")
        redactHeader("X-Private-Unlock-Token")
        redactHeader("X-Owner-Key")
    }

    private val httpDispatcher = Dispatcher().apply {
        maxRequests = 64
        maxRequestsPerHost = 12
    }

    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .dispatcher(httpDispatcher)
        .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
        .connectionPool(ConnectionPool(8, 3, TimeUnit.MINUTES))
        .retryOnConnectionFailure(true)
        .addInterceptor(AuthInterceptor(tokenStore, privateChatLockManager))
        .addInterceptor(loggingInterceptor)
        .authenticator(TokenAuthenticator(baseUrl, tokenStore, moshi))
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val wsOkHttpClient: OkHttpClient = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectionPool(ConnectionPool(2, 5, TimeUnit.MINUTES))
        .retryOnConnectionFailure(true)
        .addInterceptor(loggingInterceptor)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
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
        okHttpClient = wsOkHttpClient,
        tokenStore = tokenStore,
        prefsStore = prefsStore,
        networkMonitor = networkMonitor,
        moshi = moshi
    )

    val imageLoader: ImageLoader = ImageLoader.Builder(context)
        .okHttpClient(okHttpClient)
        .memoryCache {
            MemoryCache.Builder(context)
                .maxSizePercent(0.25)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(File(context.cacheDir, "image_cache"))
                .maxSizePercent(0.08)
                .build()
        }
        .crossfade(true)
        .build()

    fun mediaContentUrl(mediaId: String): String =
        "${baseUrl}v1/media/$mediaId/content"
}
