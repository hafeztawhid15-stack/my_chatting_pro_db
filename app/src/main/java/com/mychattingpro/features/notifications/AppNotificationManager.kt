package com.mychattingpro.features.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import com.example.R
import com.mychattingpro.MainActivity
import com.mychattingpro.core.datastore.AppPreferences
import com.mychattingpro.features.calls.IncomingCallActionReceiver
import java.util.Collections

/**
 * Privacy-safe, zero-Firebase Android Notification Manager.
 * Strictly honors per-chat mute settings, custom per-chat ringtones,
 * lockscreen privacy for Private Chat, and all NotificationSettings preferences.
 */
class AppNotificationManager(private val context: Context) {

    companion object {
        const val CHANNEL_MESSAGES = "mcp_messages_v2"
        const val CHANNEL_CALLS = "mcp_calls_v2"
        const val CHANNEL_SECURITY = "mcp_security_v2"
        const val CHANNEL_ACCOUNT = "mcp_account_v2"
        const val CHANNEL_SYNC = "mcp_realtime_sync"

        const val EXTRA_CALL_ID = "callId"
        const val EXTRA_CONVERSATION_ID = "conversationId"
        const val EXTRA_CALLER_ID = "callerId"
        const val EXTRA_CALLER_NAME = "callerName"
        const val EXTRA_CALLER_AVATAR = "callerAvatarMediaId"
        const val EXTRA_IS_VIDEO = "isVideo"
        const val EXTRA_CALL_ACTION = "callAction"
        const val CALL_ACTION_OPEN = "open"
        const val CALL_ACTION_ANSWER = "answer"
    }

    private val displayedEventIds: MutableSet<String> = Collections.synchronizedSet(
        Collections.newSetFromMap(
            object : LinkedHashMap<String, Boolean>(256, 0.75f, false) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean =
                    size > 200
            }
        )
    )

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val notifAudioAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private val soundLock = Any()
    private var activeMessageMediaPlayer: MediaPlayer? = null
    private var activeMessageRingtone: Ringtone? = null
    private var lastMessageAlertPlayedAtMs: Long = 0L

    init {
        createChannels()
    }

    fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            // Delete legacy silent channels so devices upgrade cleanly to active-sound channels.
            runCatching { nm.deleteNotificationChannel("mcp_messages") }
            runCatching { nm.deleteNotificationChannel("mcp_security") }
            runCatching { nm.deleteNotificationChannel("mcp_account") }

            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_MESSAGES,
                    context.getString(R.string.notif_channel_messages),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    setSound(Settings.System.DEFAULT_NOTIFICATION_URI, notifAudioAttributes)
                    enableVibration(true)
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                }
            )
            // Incoming call channel uses null sound because IncomingCallRingtoneController
            // dynamically queries RingtoneManager.TYPE_RINGTONE on every call and stops immediately
            // when answered/rejected/disconnected.
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_CALLS,
                    context.getString(R.string.notif_channel_calls),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    setSound(null, null)
                    enableVibration(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    setBypassDnd(true)
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_SECURITY,
                    context.getString(R.string.notif_channel_security),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    setSound(Settings.System.DEFAULT_NOTIFICATION_URI, notifAudioAttributes)
                    enableVibration(true)
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ACCOUNT,
                    context.getString(R.string.notif_channel_account),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    setSound(Settings.System.DEFAULT_NOTIFICATION_URI, notifAudioAttributes)
                    enableVibration(true)
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_SYNC,
                    context.getString(R.string.notif_channel_sync),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    private fun markEventSeen(eventId: String?): Boolean {
        if (eventId.isNullOrBlank()) return true
        synchronized(displayedEventIds) {
            return displayedEventIds.add(eventId)
        }
    }

    fun showNewMessageNotification(
        senderName: String,
        conversationId: String,
        previewText: String?,
        prefs: AppPreferences,
        mutedUntilMs: Long? = null,
        customSoundUri: String? = null,
        eventId: String? = null
    ) {
        if (!prefs.notificationsEnabled || !prefs.messageNotificationsEnabled) return
        if (mutedUntilMs != null && mutedUntilMs > 0L && mutedUntilMs > System.currentTimeMillis()) return
        if (!markEventSeen(eventId)) return

        val canPostSystemNotif = hasNotificationPermission()
        val hasCustomSound = !customSoundUri.isNullOrBlank() && prefs.messageSoundEnabled
        if (hasCustomSound) {
            playLiveMessageSound(customSoundUri = customSoundUri, prefs = prefs)
            if (!canPostSystemNotif) {
                playMessageVibration(prefs = prefs)
            }
        } else if (!canPostSystemNotif && prefs.inAppAlertsEnabled) {
            playLiveMessageSound(customSoundUri = null, prefs = prefs)
            playMessageVibration(prefs = prefs)
        }
        if (!canPostSystemNotif) return

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("conversationId", conversationId)
        }
        val pi = PendingIntent.getActivity(
            context,
            conversationId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val bodyText = if (prefs.showMessagePreview && !previewText.isNullOrBlank()) {
            previewText.take(120)
        } else {
            context.getString(R.string.notif_new_message_body)
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notif_chat)
            .setContentTitle(context.getString(R.string.notif_new_message_title, senderName))
            .setContentText(bodyText)
            .setVisibility(
                if (prefs.showMessagePreview) NotificationCompat.VISIBILITY_PRIVATE
                else NotificationCompat.VISIBILITY_SECRET
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)

        if (!prefs.messageSoundEnabled || hasCustomSound) {
            builder.setSilent(true)
            if (hasCustomSound && prefs.vibrationEnabled) {
                playMessageVibration(prefs = prefs)
            }
        } else {
            builder.setSound(resolveSystemNotificationUriForBuilder())
            if (prefs.vibrationEnabled) {
                builder.setDefaults(Notification.DEFAULT_VIBRATE)
            }
        }

        runCatching {
            NotificationManagerCompat.from(context).notify(conversationId.hashCode(), builder.build())
        }
    }

    fun showNewPrivateMessageNotification(
        prefs: AppPreferences,
        mutedUntilMs: Long? = null,
        customSoundUri: String? = null,
        eventId: String? = null
    ) {
        if (!prefs.notificationsEnabled || !prefs.privateChatNotificationsEnabled) return
        if (mutedUntilMs != null && mutedUntilMs > 0L && mutedUntilMs > System.currentTimeMillis()) return
        if (!markEventSeen(eventId)) return

        val canPostSystemNotif = hasNotificationPermission()
        val hasCustomSound = !customSoundUri.isNullOrBlank() && prefs.messageSoundEnabled
        if (hasCustomSound) {
            playLiveMessageSound(customSoundUri = customSoundUri, prefs = prefs)
            if (!canPostSystemNotif) {
                playMessageVibration(prefs = prefs)
            }
        } else if (!canPostSystemNotif && prefs.inAppAlertsEnabled) {
            playLiveMessageSound(customSoundUri = null, prefs = prefs)
            playMessageVibration(prefs = prefs)
        }
        if (!canPostSystemNotif) return

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            context,
            9101,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notif_chat)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.notif_private_message_body))
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)

        if (!prefs.messageSoundEnabled || hasCustomSound) {
            builder.setSilent(true)
            if (hasCustomSound && prefs.vibrationEnabled) {
                playMessageVibration(prefs = prefs)
            }
        } else {
            builder.setSound(resolveSystemNotificationUriForBuilder())
            if (prefs.vibrationEnabled) {
                builder.setDefaults(Notification.DEFAULT_VIBRATE)
            }
        }

        runCatching {
            NotificationManagerCompat.from(context).notify(9101, builder.build())
        }
    }

    fun showIncomingCallNotification(
        callId: String,
        callerName: String,
        isVideo: Boolean,
        prefs: AppPreferences,
        conversationId: String = "",
        callerId: String = "",
        callerAvatarMediaId: String? = null
    ) {
        if (!hasNotificationPermission()) return
        if (!prefs.notificationsEnabled || !prefs.callNotificationsEnabled) return

        fun buildActivityIntent(actionMode: String): Intent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_CALL_ID, callId)
                putExtra(EXTRA_CONVERSATION_ID, conversationId)
                putExtra(EXTRA_CALLER_ID, callerId)
                putExtra(EXTRA_CALLER_NAME, callerName)
                putExtra(EXTRA_CALLER_AVATAR, callerAvatarMediaId)
                putExtra(EXTRA_IS_VIDEO, isVideo)
                putExtra(EXTRA_CALL_ACTION, actionMode)
            }

        val fullScreenPi = PendingIntent.getActivity(
            context,
            callId.hashCode(),
            buildActivityIntent(CALL_ACTION_OPEN),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val answerPi = PendingIntent.getActivity(
            context,
            callId.hashCode() + 2,
            buildActivityIntent(CALL_ACTION_ANSWER),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val declineIntent = Intent(context, IncomingCallActionReceiver::class.java).apply {
            action = IncomingCallActionReceiver.ACTION_DECLINE_CALL
            putExtra(IncomingCallActionReceiver.EXTRA_CALL_ID, callId)
            putExtra(IncomingCallActionReceiver.EXTRA_CONVERSATION_ID, conversationId)
            putExtra(IncomingCallActionReceiver.EXTRA_CALLER_ID, callerId)
        }
        val declinePi = PendingIntent.getBroadcast(
            context,
            callId.hashCode() + 3,
            declineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val safeCallerName = callerName.ifBlank { context.getString(R.string.app_name) }
        val callerPerson = Person.Builder()
            .setName(safeCallerName)
            .setImportant(true)
            .build()

        val subtitle = if (isVideo) {
            context.getString(R.string.call_incoming_video)
        } else {
            context.getString(R.string.call_incoming_audio)
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_notif_chat)
            .setContentTitle(safeCallerName)
            .setContentText(subtitle)
            .setStyle(
                NotificationCompat.CallStyle.forIncomingCall(callerPerson, declinePi, answerPi)
                    .setIsVideo(isVideo)
            )
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setSilent(true)
            .setContentIntent(fullScreenPi)
            .setFullScreenIntent(fullScreenPi, true)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(callId.hashCode(), notification)
        }
    }

    fun showMissedCallNotification(callId: String, callerName: String, prefs: AppPreferences) {
        if (!prefs.notificationsEnabled || !prefs.missedCallNotificationsEnabled) return
        if (!markEventSeen("missed-$callId")) return

        val canPostSystemNotif = hasNotificationPermission()
        if (!canPostSystemNotif && prefs.inAppAlertsEnabled) {
            playLiveMessageSound(customSoundUri = null, prefs = prefs)
            playMessageVibration(prefs = prefs)
        }
        if (!canPostSystemNotif) return

        val builder = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notif_chat)
            .setContentTitle(callerName.ifBlank { context.getString(R.string.app_name) })
            .setContentText(context.getString(R.string.notif_missed_call_body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
        if (!prefs.messageSoundEnabled) {
            builder.setSilent(true)
        } else {
            builder.setSound(resolveSystemNotificationUriForBuilder())
            if (prefs.vibrationEnabled) {
                builder.setDefaults(Notification.DEFAULT_VIBRATE)
            }
        }
        runCatching {
            NotificationManagerCompat.from(context).notify(callId.hashCode() + 1, builder.build())
        }
    }

    fun showSecurityAlertNotification(
        title: String,
        body: String,
        alertType: String,
        prefs: AppPreferences,
        eventId: String? = null
    ) {
        val isAccountEvent = alertType == "account_deleted" || alertType == "recovery_code_updated"
        if (!prefs.notificationsEnabled) return
        if (isAccountEvent && !prefs.accountAlertsEnabled) return
        if (!isAccountEvent && !prefs.securityAlertsEnabled) return
        if (!markEventSeen(eventId)) return

        val canPostSystemNotif = hasNotificationPermission()
        if (!canPostSystemNotif && prefs.inAppAlertsEnabled) {
            playLiveMessageSound(customSoundUri = null, prefs = prefs)
            playMessageVibration(prefs = prefs)
        }
        if (!canPostSystemNotif) return

        val channel = if (isAccountEvent) CHANNEL_ACCOUNT else CHANNEL_SECURITY
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            context,
            alertType.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notif_chat)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
        if (!prefs.messageSoundEnabled) {
            builder.setSilent(true)
        } else {
            builder.setSound(resolveSystemNotificationUriForBuilder())
            if (prefs.vibrationEnabled) {
                builder.setDefaults(Notification.DEFAULT_VIBRATE)
            }
        }

        runCatching {
            NotificationManagerCompat.from(context).notify((eventId ?: alertType).hashCode(), builder.build())
        }
    }

    private fun resolveSystemNotificationUriForBuilder(): Uri =
        resolveMessageSoundCandidates(null).firstOrNull() ?: Settings.System.DEFAULT_NOTIFICATION_URI

    fun cancelCallNotification(callId: String) {
        runCatching {
            NotificationManagerCompat.from(context).cancel(callId.hashCode())
        }
    }

    private fun resolveMessageSoundCandidates(customSoundUri: String?): List<Uri> {
        val uris = linkedSetOf<Uri>()
        if (!customSoundUri.isNullOrBlank()) {
            runCatching { Uri.parse(customSoundUri) }.getOrNull()?.let { uris.add(it) }
        }
        runCatching {
            RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_NOTIFICATION)
        }.getOrNull()?.let { uris.add(it) }
        runCatching {
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        }.getOrNull()?.let { uris.add(it) }
        uris.add(Settings.System.DEFAULT_NOTIFICATION_URI)
        return uris.toList()
    }

    private fun playLiveMessageSound(customSoundUri: String?, prefs: AppPreferences) {
        if (!prefs.notificationsEnabled || !prefs.messageSoundEnabled) return
        val ringerMode = runCatching {
            audioManager?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
        }.getOrDefault(AudioManager.RINGER_MODE_NORMAL)
        if (ringerMode != AudioManager.RINGER_MODE_NORMAL) return

        val candidates = resolveMessageSoundCandidates(customSoundUri)
        if (candidates.isEmpty()) return

        synchronized(soundLock) {
            val now = System.currentTimeMillis()
            if (now - lastMessageAlertPlayedAtMs < 600L) return
            lastMessageAlertPlayedAtMs = now

            stopActiveMessageAudioLocked()

            // Pass 1: MediaPlayer verifies data source upfront and releases immediately after single playback
            var started = false
            for (candidate in candidates) {
                val mp = MediaPlayer()
                val played = runCatching {
                    mp.setAudioAttributes(notifAudioAttributes)
                    mp.setDataSource(context, candidate)
                    mp.isLooping = false
                    mp.setVolume(1.0f, 1.0f)
                    mp.setOnCompletionListener { finishedMp ->
                        synchronized(soundLock) {
                            if (activeMessageMediaPlayer === finishedMp) {
                                activeMessageMediaPlayer = null
                            }
                        }
                        runCatching { finishedMp.release() }
                    }
                    mp.setOnErrorListener { failedMp, _, _ ->
                        synchronized(soundLock) {
                            if (activeMessageMediaPlayer === failedMp) {
                                activeMessageMediaPlayer = null
                            }
                        }
                        runCatching { failedMp.release() }
                        true
                    }
                    mp.prepare()
                    mp.start()
                    activeMessageMediaPlayer = mp
                    true
                }.onFailure {
                    runCatching { mp.reset() }
                    runCatching { mp.release() }
                }.getOrDefault(false)
                if (played) {
                    started = true
                    break
                }
            }

            // Pass 2: System Ringtone fallback
            if (!started) {
                for (candidate in candidates) {
                    val played = runCatching {
                        val ringtone = RingtoneManager.getRingtone(context, candidate) ?: return@runCatching false
                        ringtone.audioAttributes = notifAudioAttributes
                        ringtone.play()
                        activeMessageRingtone = ringtone
                        true
                    }.getOrDefault(false)
                    if (played) break
                }
            }
        }
    }

    private fun stopActiveMessageAudioLocked() {
        runCatching { activeMessageRingtone?.stop() }
        activeMessageRingtone = null
        runCatching {
            activeMessageMediaPlayer?.let { mp ->
                if (mp.isPlaying) mp.stop()
                mp.release()
            }
        }
        activeMessageMediaPlayer = null
    }

    private fun playMessageVibration(prefs: AppPreferences) {
        if (!prefs.notificationsEnabled || !prefs.vibrationEnabled) return
        val ringerMode = runCatching {
            audioManager?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
        }.getOrDefault(AudioManager.RINGER_MODE_NORMAL)
        if (ringerMode == AudioManager.RINGER_MODE_SILENT) return

        val vibrator = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        }.getOrNull() ?: return

        if (!vibrator.hasVibrator()) return
        val pattern = longArrayOf(0L, 180L, 90L, 180L)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val attrs = VibrationAttributes.Builder()
                    .setUsage(VibrationAttributes.USAGE_NOTIFICATION)
                    .build()
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1), attrs)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                @Suppress("DEPRECATION")
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1), notifAudioAttributes)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, -1)
            }
        }
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
}
