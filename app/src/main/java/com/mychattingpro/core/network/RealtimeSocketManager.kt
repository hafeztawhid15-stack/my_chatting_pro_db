package com.mychattingpro.core.network

import com.mychattingpro.core.datastore.AppPreferencesDataStore
import com.mychattingpro.core.security.EncryptedTokenStore
import com.mychattingpro.core.security.SafeLogger
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.Collections

enum class SocketConnectionState {
    DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING
}

sealed interface RealtimeEvent {
    data class MessageNew(val message: MessageDto) : RealtimeEvent
    data class MessageDelivered(val conversationId: String, val messageIds: List<String>, val atMs: Long, val isPrivate: Boolean = false) : RealtimeEvent
    data class MessageRead(val conversationId: String, val messageIds: List<String>, val atMs: Long, val isPrivate: Boolean = false) : RealtimeEvent
    data class MessageDeleted(val conversationId: String, val messageId: String, val deletedAtMs: Long, val isPrivate: Boolean = false) : RealtimeEvent
    data class TypingChanged(val conversationId: String, val fromUserId: String, val isTyping: Boolean) : RealtimeEvent
    data class PresenceChanged(val userId: String, val isOnline: Boolean, val lastSeenMs: Long?) : RealtimeEvent
    data class CallInvite(
        val callId: String,
        val conversationId: String,
        val callerId: String,
        val callerName: String,
        val callerAvatarMediaId: String?,
        val isVideo: Boolean
    ) : RealtimeEvent
    data class CallSignal(
        val type: String,
        val callId: String,
        val conversationId: String,
        val fromUserId: String,
        val payload: JSONObject
    ) : RealtimeEvent
    data class CallMissed(
        val callId: String,
        val peerName: String
    ) : RealtimeEvent
    data class CallStateChanged(
        val callId: String,
        val status: String,
        val durationSeconds: Int
    ) : RealtimeEvent
    data class SecurityAlert(
        val alertType: String,
        val title: String,
        val body: String,
        val eventId: String
    ) : RealtimeEvent
}

class RealtimeSocketManager(
    private val baseUrl: String,
    private val okHttpClient: OkHttpClient,
    private val tokenStore: EncryptedTokenStore,
    private val prefsStore: AppPreferencesDataStore,
    private val networkMonitor: NetworkMonitor,
    private val moshi: Moshi? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshLock = Any()
    private val refreshClient = OkHttpClient.Builder().build()

    private val _connectionState = MutableStateFlow(SocketConnectionState.DISCONNECTED)
    val connectionState: StateFlow<SocketConnectionState> = _connectionState.asStateFlow()

    private val _events = MutableSharedFlow<RealtimeEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<RealtimeEvent> = _events.asSharedFlow()

    @Volatile
    private var activeSocket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    @Volatile
    private var shouldStayConnected = false
    private var backoffAttempt = 0
    private val pendingOutboundSignals = Collections.synchronizedList(mutableListOf<String>())

    init {
        scope.launch {
            var previousOnline = networkMonitor.isOnline.value
            networkMonitor.isOnline.collect { online ->
                if (online && !previousOnline && shouldStayConnected) {
                    backoffAttempt = 0
                    reconnectJob?.cancel()
                    connectIfNeeded()
                }
                previousOnline = online
            }
        }
    }

    // Bounded LRU set for idempotent deduplication of incoming Durable Object eventIds
    private val seenEventIds: MutableSet<String> = Collections.synchronizedSet(
        Collections.newSetFromMap(
            object : LinkedHashMap<String, Boolean>(512, 0.75f, false) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean =
                    size > 500
            }
        )
    )

    fun start() {
        shouldStayConnected = true
        connectIfNeeded()
    }

    fun sendPresencePing() {
        val ws = activeSocket
        if (ws != null && _connectionState.value == SocketConnectionState.CONNECTED) {
            if (ws.send("""{"type":"ping"}""")) return
        }
        start()
    }

    fun pauseForBackground() {
        shouldStayConnected = false
        reconnectJob?.cancel()
        heartbeatJob?.cancel()
        val ws = activeSocket
        activeSocket = null
        runCatching { ws?.send("""{"type":"presence.offline"}""") }
        runCatching { ws?.close(1000, "App backgrounded") }
        _connectionState.value = SocketConnectionState.DISCONNECTED
    }

    fun stop() {
        shouldStayConnected = false
        reconnectJob?.cancel()
        heartbeatJob?.cancel()
        pendingOutboundSignals.clear()
        val ws = activeSocket
        activeSocket = null
        runCatching { ws?.send("""{"type":"presence.offline"}""") }
        runCatching { ws?.close(1000, "User stopped") }
        _connectionState.value = SocketConnectionState.DISCONNECTED
    }

    fun sendSignaling(type: String, payload: JSONObject) {
        val envelope = JSONObject().apply {
            put("type", type)
            put("payload", payload)
        }.toString()
        val ws = activeSocket
        if (ws != null && _connectionState.value == SocketConnectionState.CONNECTED) {
            if (ws.send(envelope)) return
        }
        if (type.startsWith("call.")) {
            shouldStayConnected = true
            synchronized(pendingOutboundSignals) {
                if (pendingOutboundSignals.size >= 48) {
                    pendingOutboundSignals.removeAt(0)
                }
                pendingOutboundSignals.add(envelope)
            }
            connectIfNeeded()
        }
    }

    fun sendTyping(conversationId: String, targetUserId: String, isTyping: Boolean) {
        val payload = JSONObject().apply {
            put("conversationId", conversationId)
            put("targetUserId", targetUserId)
        }
        sendSignaling(if (isTyping) "typing.start" else "typing.stop", payload)
    }

    private fun connectIfNeeded() {
        if (!shouldStayConnected) return
        if (_connectionState.value == SocketConnectionState.CONNECTED ||
            _connectionState.value == SocketConnectionState.CONNECTING
        ) return

        scope.launch {
            val session = tokenStore.getSession() ?: run {
                _connectionState.value = SocketConnectionState.DISCONNECTED
                return@launch
            }
            if (!networkMonitor.isOnline.value) {
                scheduleReconnect()
                return@launch
            }

            _connectionState.value = if (backoffAttempt > 0) {
                SocketConnectionState.RECONNECTING
            } else {
                SocketConnectionState.CONNECTING
            }

            val sinceMs = prefsStore.getLastRealtimeEventMs()
            val wsUrl = baseUrl.trimEnd('/')
                .replaceFirst("https://", "wss://")
                .replaceFirst("http://", "ws://") + "/v1/ws?sinceMs=$sinceMs"

            val request = Request.Builder()
                .url(wsUrl)
                .header("Authorization", "Bearer ${session.accessToken}")
                .build()

            activeSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (webSocket !== activeSocket) {
                        webSocket.close(1000, "Superseded")
                        return
                    }
                    backoffAttempt = 0
                    _connectionState.value = SocketConnectionState.CONNECTED
                    flushPendingSignals(webSocket)
                    startHeartbeat(webSocket)
                    SafeLogger.d("RealtimeWS", "Connected to Cloudflare Durable Object")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleIncomingFrame(text)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (webSocket === activeSocket) {
                        cleanupSocketAndReconnect()
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    SafeLogger.w("RealtimeWS", "Socket failure (${response?.code ?: 0}): ${t.javaClass.simpleName}")
                    if (webSocket !== activeSocket) return
                    if (response?.code == 401) {
                        heartbeatJob?.cancel()
                        activeSocket = null
                        scope.launch {
                            val refreshed = refreshAccessTokenSync()
                            if (refreshed && shouldStayConnected) {
                                backoffAttempt = 0
                                _connectionState.value = SocketConnectionState.RECONNECTING
                                connectIfNeeded()
                            } else if (tokenStore.getSession() == null) {
                                stop()
                            } else {
                                cleanupSocketAndReconnect()
                            }
                        }
                        return
                    }
                    cleanupSocketAndReconnect()
                }
            })
        }
    }

    private fun refreshAccessTokenSync(): Boolean {
        synchronized(refreshLock) {
            val session = runBlocking { tokenStore.getSession() } ?: return false
            return try {
                val reqJson = JSONObject().apply {
                    put("refreshToken", session.refreshToken)
                }.toString()
                val req = Request.Builder()
                    .url("${baseUrl.trimEnd('/')}/v1/auth/refresh")
                    .post(reqJson.toRequestBody("application/json".toMediaType()))
                    .build()
                refreshClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        if (resp.code == 401) {
                            runBlocking { tokenStore.clearSession() }
                        }
                        return false
                    }
                    val raw = resp.body?.string() ?: return false
                    val parsedAccess: String
                    val parsedRefresh: String
                    val adapter = moshi?.adapter(RefreshResponseDto::class.java)
                    if (adapter != null) {
                        val dto = adapter.fromJson(raw) ?: return false
                        parsedAccess = dto.accessToken
                        parsedRefresh = dto.refreshToken
                    } else {
                        val json = JSONObject(raw)
                        parsedAccess = json.optString("accessToken")
                        parsedRefresh = json.optString("refreshToken")
                    }
                    if (parsedAccess.isBlank() || parsedRefresh.isBlank()) return false
                    runBlocking { tokenStore.updateTokens(parsedAccess, parsedRefresh) }
                    true
                }
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun flushPendingSignals(webSocket: WebSocket) {
        val snapshot: List<String>
        synchronized(pendingOutboundSignals) {
            if (pendingOutboundSignals.isEmpty()) return
            snapshot = pendingOutboundSignals.toList()
            pendingOutboundSignals.clear()
        }
        snapshot.forEach { frame ->
            webSocket.send(frame)
        }
    }

    private fun startHeartbeat(webSocket: WebSocket) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(25_000)
                val sent = webSocket.send("""{"type":"ping"}""")
                if (!sent) break
            }
        }
    }

    private fun cleanupSocketAndReconnect() {
        heartbeatJob?.cancel()
        activeSocket = null
        if (shouldStayConnected) {
            _connectionState.value = SocketConnectionState.RECONNECTING
            scheduleReconnect()
        } else {
            _connectionState.value = SocketConnectionState.DISCONNECTED
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val delayMs = (1500L * (1 shl backoffAttempt.coerceAtMost(5))).coerceAtMost(30_000L)
            backoffAttempt++
            delay(delayMs)
            connectIfNeeded()
        }
    }

    private fun handleIncomingFrame(raw: String) {
        try {
            val root = JSONObject(raw)
            val eventId = root.optString("eventId")
            val type = root.optString("type")
            val ts = root.optLong("timestampMs", 0L)
            val payload = root.optJSONObject("payload") ?: JSONObject()

            if (eventId.isNotBlank()) {
                synchronized(seenEventIds) {
                    if (!seenEventIds.add(eventId)) return
                    if (seenEventIds.size > 500) {
                        seenEventIds.iterator().let { it.next(); it.remove() }
                    }
                }
            }

            val isTransientCallSignal = type == "call.invite" ||
                type == "call.ringing" ||
                type == "call.accept" ||
                type == "call.offer" ||
                type == "call.answer" ||
                type == "call.ice"
            if (isTransientCallSignal && ts > 0L && (System.currentTimeMillis() - ts) > 90_000L) {
                return
            }

            if (ts > 0L) {
                scope.launch { prefsStore.updateLastRealtimeEventMs(ts) }
            }

            when (type) {
                "message.new" -> {
                    val msgObj = payload.optJSONObject("message") ?: return
                    val mediaObj = msgObj.optJSONObject("media")
                    val isPrivate = msgObj.optBoolean("isPrivate", payload.optBoolean("isPrivate", false))
                    val dto = MessageDto(
                        id = msgObj.getString("id"),
                        conversationId = msgObj.getString("conversationId"),
                        senderId = msgObj.getString("senderId"),
                        clientMessageId = msgObj.optString("clientMessageId", msgObj.getString("id")),
                        messageType = msgObj.optString("messageType", "text"),
                        bodyText = if (msgObj.isNull("bodyText")) null else msgObj.optString("bodyText"),
                        media = mediaObj?.let {
                            MediaObjectDto(
                                id = it.getString("id"),
                                category = it.optString("category", "file"),
                                originalFilename = it.optString("originalFilename", "file"),
                                mimeType = it.optString("mimeType", "application/octet-stream"),
                                sizeBytes = it.optLong("sizeBytes", 0L),
                                durationMs = if (it.isNull("durationMs")) null else it.optLong("durationMs")
                            )
                        },
                        replyToMessageId = if (msgObj.isNull("replyToMessageId")) null else msgObj.optString("replyToMessageId").takeIf { it.isNotBlank() },
                        createdAtMs = msgObj.getLong("createdAtMs"),
                        deliveredAtMs = if (msgObj.isNull("deliveredAtMs")) null else msgObj.optLong("deliveredAtMs"),
                        readAtMs = if (msgObj.isNull("readAtMs")) null else msgObj.optLong("readAtMs"),
                        isPrivate = isPrivate
                    )
                    _events.tryEmit(RealtimeEvent.MessageNew(dto))
                }
                "message.delivered", "message.read" -> {
                    val convId = payload.optString("conversationId")
                    val arr = payload.optJSONArray("messageIds") ?: return
                    val ids = (0 until arr.length()).map { arr.getString(it) }
                    val atMs = payload.optLong("atMs", System.currentTimeMillis())
                    val isPrivate = payload.optBoolean("isPrivate", false)
                    if (type == "message.delivered") {
                        _events.tryEmit(RealtimeEvent.MessageDelivered(convId, ids, atMs, isPrivate))
                    } else {
                        _events.tryEmit(RealtimeEvent.MessageRead(convId, ids, atMs, isPrivate))
                    }
                }
                "message.deleted" -> {
                    _events.tryEmit(
                        RealtimeEvent.MessageDeleted(
                            conversationId = payload.optString("conversationId"),
                            messageId = payload.optString("messageId"),
                            deletedAtMs = payload.optLong("deletedAtMs", System.currentTimeMillis()),
                            isPrivate = payload.optBoolean("isPrivate", false)
                        )
                    )
                }
                "typing.start", "typing.stop" -> {
                    _events.tryEmit(
                        RealtimeEvent.TypingChanged(
                            conversationId = payload.optString("conversationId"),
                            fromUserId = payload.optString("fromUserId"),
                            isTyping = type == "typing.start"
                        )
                    )
                }
                "presence.update" -> {
                    _events.tryEmit(
                        RealtimeEvent.PresenceChanged(
                            userId = payload.optString("userId"),
                            isOnline = payload.optBoolean("isOnline", false),
                            lastSeenMs = if (payload.isNull("lastSeenMs")) null else payload.optLong("lastSeenMs")
                        )
                    )
                }
                "call.invite" -> {
                    _events.tryEmit(
                        RealtimeEvent.CallInvite(
                            callId = payload.optString("callId"),
                            conversationId = payload.optString("conversationId"),
                            callerId = payload.optString("callerId"),
                            callerName = payload.optString("callerName", "MY CHATTING PRO"),
                            callerAvatarMediaId = if (payload.isNull("callerAvatarMediaId")) null else payload.optString("callerAvatarMediaId"),
                            isVideo = payload.optString("callType") == "video"
                        )
                    )
                }
                "call.missed" -> {
                    _events.tryEmit(
                        RealtimeEvent.CallMissed(
                            callId = payload.optString("callId"),
                            peerName = payload.optString("peerName", "MY CHATTING PRO")
                        )
                    )
                }
                "call.state" -> {
                    _events.tryEmit(
                        RealtimeEvent.CallStateChanged(
                            callId = payload.optString("callId"),
                            status = payload.optString("status"),
                            durationSeconds = payload.optInt("durationSeconds", 0)
                        )
                    )
                }
                "call.ringing", "call.accept", "call.reject", "call.offer", "call.answer", "call.ice", "call.end" -> {
                    _events.tryEmit(
                        RealtimeEvent.CallSignal(
                            type = type,
                            callId = payload.optString("callId"),
                            conversationId = payload.optString("conversationId"),
                            fromUserId = payload.optString("fromUserId"),
                            payload = payload
                        )
                    )
                }
                "security.alert" -> {
                    _events.tryEmit(
                        RealtimeEvent.SecurityAlert(
                            alertType = payload.optString("alertType", "security"),
                            title = payload.optString("title", "Account Security Alert"),
                            body = payload.optString("body", "Security activity detected on your account."),
                            eventId = eventId
                        )
                    )
                }
            }
        } catch (e: Exception) {
            SafeLogger.e("RealtimeWS", "Failed to parse event", e.javaClass.simpleName)
        }
    }
}
