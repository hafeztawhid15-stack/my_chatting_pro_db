package com.mychattingpro

import android.app.Application
import android.content.Context
import com.mychattingpro.core.database.MyChattingProDatabase
import com.mychattingpro.core.datastore.AppPreferencesDataStore
import com.mychattingpro.core.network.NetworkModule
import com.mychattingpro.core.network.RealtimeEvent
import com.mychattingpro.core.security.EncryptedTokenStore
import com.mychattingpro.data.repository.AuthRepositoryImpl
import com.mychattingpro.data.repository.CallRepositoryImpl
import com.mychattingpro.data.repository.ChatRepositoryImpl
import com.mychattingpro.data.repository.ErrorMapper
import com.mychattingpro.data.repository.MessageRepositoryImpl
import com.mychattingpro.data.repository.OwnerRepositoryImpl
import com.mychattingpro.data.repository.ProfileRepositoryImpl
import com.mychattingpro.data.repository.SafetyRepositoryImpl
import com.mychattingpro.domain.repository.AuthRepository
import com.mychattingpro.domain.repository.CallRepository
import com.mychattingpro.domain.repository.ChatRepository
import com.mychattingpro.domain.repository.MessageRepository
import com.mychattingpro.domain.repository.OwnerRepository
import com.mychattingpro.domain.repository.ProfileRepository
import com.mychattingpro.domain.repository.SafetyRepository
import com.mychattingpro.features.ads.InterstitialAdManager
import com.mychattingpro.features.notifications.AppNotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
    val database = MyChattingProDatabase.getInstance(appContext)
    val networkModule = NetworkModule(appContext, tokenStore, prefsStore)
    val notificationManager = AppNotificationManager(appContext)
    val errorMapper = ErrorMapper(networkModule.moshi)

    val authRepository: AuthRepository = AuthRepositoryImpl(
        context = appContext,
        api = networkModule.apiService,
        tokenStore = tokenStore,
        conversationDao = database.conversationDao(),
        messageDao = database.messageDao(),
        socketManager = networkModule.realtimeSocketManager,
        errorMapper = errorMapper
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
        errorMapper = errorMapper
    )

    val messageRepository: MessageRepository = MessageRepositoryImpl(
        context = appContext,
        api = networkModule.apiService,
        messageDao = database.messageDao(),
        conversationDao = database.conversationDao(),
        tokenStore = tokenStore,
        errorMapper = errorMapper
    )

    val safetyRepository: SafetyRepository = SafetyRepositoryImpl(
        api = networkModule.apiService,
        conversationDao = database.conversationDao(),
        errorMapper = errorMapper
    )

    val callRepository: CallRepository = CallRepositoryImpl(
        api = networkModule.apiService,
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

    var activeConversationId: String? = null

    init {
        appScope.launch {
            networkModule.realtimeSocketManager.events.collect { event ->
                when (event) {
                    is RealtimeEvent.CallInvite -> {
                        val prompt = IncomingCallPrompt(
                            callId = event.callId,
                            conversationId = event.conversationId,
                            callerId = event.callerId,
                            callerName = event.callerName,
                            callerAvatarMediaId = event.callerAvatarMediaId,
                            isVideo = event.isVideo
                        )
                        _incomingCall.value = prompt
                        notificationManager.showIncomingCallNotification(
                            callId = event.callId,
                            callerName = event.callerName,
                            isVideo = event.isVideo
                        )
                    }
                    is RealtimeEvent.CallSignal -> {
                        if (event.type == "call.end" || event.type == "call.reject") {
                            if (_incomingCall.value?.callId == event.callId) {
                                _incomingCall.value = null
                                notificationManager.cancelCallNotification(event.callId)
                            }
                        }
                    }
                    is RealtimeEvent.MessageNew -> {
                        val myId = tokenStore.getSession()?.userId
                        if (myId != null &&
                            event.message.senderId != myId &&
                            event.message.conversationId != activeConversationId
                        ) {
                            notificationManager.showNewMessageNotification(
                                senderName = "MY CHATTING PRO",
                                conversationId = event.message.conversationId
                            )
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    fun clearIncomingCall() {
        _incomingCall.value?.let { notificationManager.cancelCallNotification(it.callId) }
        _incomingCall.value = null
    }
}

class MyChattingProApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.interstitialAdManager.initialize()
    }
}
