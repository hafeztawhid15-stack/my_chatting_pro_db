package com.mychattingpro

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import com.example.R
import com.mychattingpro.core.database.MyChattingProDatabase
import com.mychattingpro.core.datastore.AppPreferencesDataStore
import com.mychattingpro.core.network.NetworkModule
import com.mychattingpro.core.network.RealtimeEvent
import com.mychattingpro.core.security.AndroidSimVerificationProvider
import com.mychattingpro.core.security.EncryptedTokenStore
import com.mychattingpro.core.security.PrivateChatLockManager
import com.mychattingpro.core.security.SimVerificationProvider
import com.mychattingpro.data.repository.AuthRepositoryImpl
import com.mychattingpro.data.repository.CallRepositoryImpl
import com.mychattingpro.data.repository.ChatRepositoryImpl
import com.mychattingpro.data.repository.ErrorMapper
import com.mychattingpro.data.repository.MessageRepositoryImpl
import com.mychattingpro.data.repository.OwnerRepositoryImpl
import com.mychattingpro.data.repository.PrivateChatRepositoryImpl
import com.mychattingpro.data.repository.ProfileRepositoryImpl
import com.mychattingpro.data.repository.SafetyRepositoryImpl
import com.mychattingpro.domain.repository.AuthRepository
import com.mychattingpro.domain.repository.CallRepository
import com.mychattingpro.domain.repository.ChatRepository
import com.mychattingpro.domain.repository.MessageRepository
import com.mychattingpro.domain.repository.OwnerRepository
import com.mychattingpro.domain.repository.PrivateChatRepository
import com.mychattingpro.domain.repository.ProfileRepository
import com.mychattingpro.domain.repository.SafetyRepository
import com.mychattingpro.features.ads.InterstitialAdManager
import com.mychattingpro.features.calls.IncomingCallRingtoneController
import com.mychattingpro.features.notifications.AppNotificationManager
import com.mychattingpro.features.notifications.BackgroundSyncJobService
import com.mychattingpro.features.settings.AppUpdateChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

data class IncomingCallPrompt(
    val callId: String,
    val conversationId: String,
    val callerId: String,
    val callerName: String,
    val callerAvatarMediaId: String?,
    val isVideo: Boolean
)

class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val tokenStore = EncryptedTokenStore(appContext)
    val prefsStore = AppPreferencesDataStore(appContext)
    val privateChatLockManager = PrivateChatLockManager()
    val database = MyChattingProDatabase.getInstance(appContext)
    val networkModule = NetworkModule(appContext, tokenStore, prefsStore, privateChatLockManager)
    val notificationManager = AppNotificationManager(appContext)
    val simVerificationProvider: SimVerificationProvider = AndroidSimVerificationProvider(appContext)
    val ringtoneController = IncomingCallRingtoneController(appContext)
    val errorMapper = ErrorMapper(networkModule.moshi, networkModule.networkMonitor)
    val appUpdateChecker = AppUpdateChecker(networkModule.okHttpClient)

    val authRepository: AuthRepository = AuthRepositoryImpl(
        context = appContext,
        api = networkModule.apiService,
        tokenStore = tokenStore,
        prefsStore = prefsStore,
        conversationDao = database.conversationDao(),
        messageDao = database.messageDao(),
        callHistoryDao = database.callHistoryDao(),
        socketManager = networkModule.realtimeSocketManager,
        errorMapper = errorMapper,
        privateChatLockManager = privateChatLockManager
    )

    val profileRepository: ProfileRepository = ProfileRepositoryImpl(
        context = appContext,
        api = networkModule.apiService,
        tokenStore = tokenStore,
        errorMapper = errorMapper
    )

    val chatRepository: ChatRepository = ChatRepositoryImpl(
        api = networkModule.apiService,
        conversationDao = database.conversationDao(),
        messageDao = database.messageDao(),
        socketManager = networkModule.realtimeSocketManager,
        tokenStore = tokenStore,
        errorMapper = errorMapper,
        privateChatLockManager = privateChatLockManager
    )

    val privateChatRepository: PrivateChatRepository = PrivateChatRepositoryImpl(
        context = appContext,
        api = networkModule.apiService,
        conversationDao = database.conversationDao(),
        messageDao = database.messageDao(),
        tokenStore = tokenStore,
        lockManager = privateChatLockManager,
        errorMapper = errorMapper
    )

    val messageRepository: MessageRepository = MessageRepositoryImpl(
        context = appContext,
        api = networkModule.apiService,
        messageDao = database.messageDao(),
        conversationDao = database.conversationDao(),
        tokenStore = tokenStore,
        errorMapper = errorMapper,
        privateChatLockManager = privateChatLockManager
    )

    val safetyRepository: SafetyRepository = SafetyRepositoryImpl(
        api = networkModule.apiService,
        conversationDao = database.conversationDao(),
        errorMapper = errorMapper
    )

    val callRepository: CallRepository = CallRepositoryImpl(
        api = networkModule.apiService,
        callHistoryDao = database.callHistoryDao(),
        errorMapper = errorMapper
    )

    val ownerRepository: OwnerRepository = OwnerRepositoryImpl(
        api = networkModule.apiService,
        errorMapper = errorMapper
    )

    val interstitialAdManager = InterstitialAdManager(appContext)

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _incomingCall = MutableStateFlow<IncomingCallPrompt?>(null)
    val incomingCall: StateFlow<IncomingCallPrompt?> = _incomingCall.asStateFlow()

    private val _securityAlerts = MutableSharedFlow<RealtimeEvent.SecurityAlert>(extraBufferCapacity = 16)
    val securityAlerts: SharedFlow<RealtimeEvent.SecurityAlert> = _securityAlerts.asSharedFlow()

    private val _externalCallAccept = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val externalCallAccept: SharedFlow<String> = _externalCallAccept.asSharedFlow()

    @Volatile
    var activeConversationId: String? = null

    @Volatile
    var isAppInForeground: Boolean = false

    init {
        appScope.launch {
            var wasOnline = networkModule.networkMonitor.isOnline.value
            networkModule.networkMonitor.isOnline.collect { online ->
                if (online && !wasOnline) {
                    interstitialAdManager.preload(resetRetry = true)
                    if (tokenStore.getSession() != null) {
                        runCatching { messageRepository.retryAllPendingMessages() }
                        runCatching { chatRepository.refreshConversations() }
                        runCatching { authRepository.syncNotificationEndpoint() }
                    }
                }
                wasOnline = online
            }
        }

        appScope.launch {
            networkModule.realtimeSocketManager.events.collect { event ->
                val prefs = prefsStore.current()
                when (event) {
                    is RealtimeEvent.CallInvite -> {
                        val conv = database.conversationDao().getById(event.conversationId)
                        if (conv?.isBlockedByMe == true || conv?.hasBlockedMe == true) {
                            return@collect
                        }
                        val prompt = IncomingCallPrompt(
                            callId = event.callId,
                            conversationId = event.conversationId,
                            callerId = event.callerId,
                            callerName = event.callerName,
                            callerAvatarMediaId = event.callerAvatarMediaId,
                            isVideo = event.isVideo
                        )
                        if (prefs.notificationsEnabled && prefs.callNotificationsEnabled) {
                            ringtoneController.startRinging(
                                callId = event.callId,
                                allowVibration = prefs.callVibrationEnabled
                            )
                        }
                        _incomingCall.value = prompt
                        notificationManager.showIncomingCallNotification(
                            callId = event.callId,
                            callerName = event.callerName,
                            isVideo = event.isVideo,
                            prefs = prefs,
                            conversationId = event.conversationId,
                            callerId = event.callerId,
                            callerAvatarMediaId = event.callerAvatarMediaId
                        )
                    }
                    is RealtimeEvent.CallMissed -> {
                        stopIncomingCallAlert(event.callId)
                        if (_incomingCall.value?.callId == event.callId) {
                            _incomingCall.value = null
                        }
                        notificationManager.showMissedCallNotification(event.callId, event.peerName, prefs)
                    }
                    is RealtimeEvent.CallSignal -> {
                        if (event.type == "call.end" || event.type == "call.reject" || event.type == "call.accept") {
                            stopIncomingCallAlert(event.callId)
                            if (_incomingCall.value?.callId == event.callId) {
                                _incomingCall.value = null
                            }
                        }
                    }
                    is RealtimeEvent.MessageNew -> {
                        val myId = tokenStore.currentSessionOrNull()?.userId ?: tokenStore.getSession()?.userId
                        if (myId != null &&
                            event.message.senderId != myId &&
                            event.message.conversationId != activeConversationId
                        ) {
                            val conv = database.conversationDao().getById(event.message.conversationId)
                            if (conv?.isBlockedByMe == true || conv?.hasBlockedMe == true) {
                                return@collect
                            }
                            val mutedUntil = conv?.mutedUntilMs
                            if (mutedUntil != null && mutedUntil > System.currentTimeMillis()) {
                                return@collect
                            }
                            val effectivePrivate = event.message.isPrivate || (conv?.isPrivate == true)
                            if (effectivePrivate) {
                                notificationManager.showNewPrivateMessageNotification(
                                    prefs = prefs,
                                    mutedUntilMs = conv?.mutedUntilMs,
                                    customSoundUri = conv?.customSoundUri,
                                    eventId = event.message.id,
                                    isAppForeground = isAppInForeground
                                )
                            } else {
                                val senderTitle = conv?.peerDisplayName?.ifBlank { "MY CHATTING PRO" } ?: "MY CHATTING PRO"
                                val previewBody = event.message.bodyText?.takeIf { it.isNotBlank() }
                                    ?: when (event.message.messageType.lowercase()) {
                                        "image" -> appContext.getString(R.string.reply_quoted_photo)
                                        "video" -> appContext.getString(R.string.reply_quoted_video)
                                        "voice" -> appContext.getString(R.string.reply_quoted_voice)
                                        "audio" -> appContext.getString(R.string.reply_quoted_audio)
                                        "file" -> event.message.media?.originalFilename
                                            ?.takeIf { it.isNotBlank() }
                                            ?: appContext.getString(R.string.reply_quoted_file)
                                        else -> appContext.getString(R.string.notif_new_message_body)
                                    }
                                notificationManager.showNewMessageNotification(
                                    senderName = senderTitle,
                                    conversationId = event.message.conversationId,
                                    previewText = previewBody,
                                    prefs = prefs,
                                    mutedUntilMs = conv?.mutedUntilMs,
                                    customSoundUri = conv?.customSoundUri,
                                    eventId = event.message.id,
                                    isAppForeground = isAppInForeground
                                )
                            }
                        }
                    }
                    is RealtimeEvent.BlockStatusChanged -> {
                        val currentCall = _incomingCall.value
                        if (currentCall != null &&
                            event.blockedBetween &&
                            (currentCall.callerId == event.actorUserId || currentCall.callerId == event.targetUserId)
                        ) {
                            stopIncomingCallAlert(currentCall.callId)
                            _incomingCall.value = null
                        }
                    }
                    is RealtimeEvent.SecurityAlert -> {
                        notificationManager.showSecurityAlertNotification(
                            title = event.title,
                            body = event.body,
                            alertType = event.alertType,
                            prefs = prefs,
                            eventId = event.eventId
                        )
                        _securityAlerts.tryEmit(event)
                        if (event.alertType == "account_deleted" || event.alertType == "session_revoked" || event.alertType == "logout_all_devices") {
                            if (event.alertType != "login_pin_changed") {
                                runCatching { authRepository.logout() }
                            }
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    fun requestExternalCallAccept(callId: String) {
        if (callId.isNotBlank()) {
            _externalCallAccept.tryEmit(callId)
        }
    }

    fun consumeIncomingCallPrompt() {
        _incomingCall.value = null
    }

    fun stopIncomingCallAlert(callId: String? = null) {
        ringtoneController.stopRinging(callId = callId, force = callId.isNullOrBlank())
        if (!callId.isNullOrBlank()) {
            notificationManager.cancelCallNotification(callId)
        }
    }

    fun rejectIncomingCallFromAlert(
        callId: String,
        conversationId: String,
        callerId: String
    ) {
        stopIncomingCallAlert(callId)
        if (_incomingCall.value?.callId == callId) {
            _incomingCall.value = null
        }
        appScope.launch {
            runCatching { callRepository.updateCallState(callId, "reject") }
            runCatching {
                networkModule.realtimeSocketManager.sendSignaling(
                    "call.reject",
                    JSONObject().apply {
                        put("callId", callId)
                        put("conversationId", conversationId)
                        put("targetUserId", callerId)
                    }
                )
            }
        }
    }

    fun clearIncomingCall() {
        val currentId = _incomingCall.value?.callId
        stopIncomingCallAlert(currentId)
        _incomingCall.value = null
    }
}

class MyChattingProApp : Application() {
    lateinit var container: AppContainer
        private set

    private var startedActivityCount = 0

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivityCount++
                container.isAppInForeground = startedActivityCount > 0
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
                container.isAppInForeground = startedActivityCount > 0
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
        container.interstitialAdManager.attachApplication(this)
        container.interstitialAdManager.initialize()
        BackgroundSyncJobService.schedule(this)
    }
}
