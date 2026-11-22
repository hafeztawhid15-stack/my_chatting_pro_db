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
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
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
        const val CHANNEL_MESSAGES = "mcp_messages"
        const val CHANNEL_CALLS = "mcp_calls_v2"
        const val CHANNEL_SECURITY = "mcp_security"
        const val CHANNEL_ACCOUNT = "mcp_account"
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

    init {
        createChannels()
    }

    fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_MESSAGES,
                    context.getString(R.string.notif_channel_messages),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
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
                    enableVibration(true)
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ACCOUNT,
                    context.getString(R.string.notif_channel_account),
                    NotificationManager.IMPORTANCE_DEFAULT
                )
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
        if (!hasNotificationPermission()) return
        if (!prefs.notificationsEnabled || !prefs.messageNotificationsEnabled) return
        if (mutedUntilMs != null && mutedUntilMs > System.currentTimeMillis()) return
        if (!markEventSeen(eventId)) return

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

        val hasCustomSound = !customSoundUri.isNullOrBlank() && prefs.messageSoundEnabled
        if (hasCustomSound) {
            playCustomMessageSound(customSoundUri!!)
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
        }
        if (prefs.vibrationEnabled && !hasCustomSound) {
            builder.setDefaults(Notification.DEFAULT_VIBRATE)
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
        if (!hasNotificationPermission()) return
        if (!prefs.notificationsEnabled || !prefs.privateChatNotificationsEnabled) return
        if (mutedUntilMs != null && mutedUntilMs > System.currentTimeMillis()) return
        if (!markEventSeen(eventId)) return

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            context,
            9101,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val hasCustomSound = !customSoundUri.isNullOrBlank() && prefs.messageSoundEnabled
        if (hasCustomSound) {
            playCustomMessageSound(customSoundUri!!)
        }

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
        if (!hasNotificationPermission()) return
        if (!prefs.notificationsEnabled || !prefs.missedCallNotificationsEnabled) return
        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notif_chat)
            .setContentTitle(callerName.ifBlank { context.getString(R.string.app_name) })
            .setContentText(context.getString(R.string.notif_missed_call_body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(callId.hashCode() + 1, notification)
        }
    }

    fun showSecurityAlertNotification(
        title: String,
        body: String,
        alertType: String,
        prefs: AppPreferences,
        eventId: String? = null
    ) {
        if (!hasNotificationPermission()) return
        val isAccountEvent = alertType == "account_deleted" || alertType == "recovery_code_updated"
        if (!prefs.notificationsEnabled) return
        if (isAccountEvent && !prefs.accountAlertsEnabled) return
        if (!isAccountEvent && !prefs.securityAlertsEnabled) return
        if (!markEventSeen(eventId)) return

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
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notif_chat)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify((eventId ?: alertType).hashCode(), notification)
        }
    }

    fun cancelCallNotification(callId: String) {
        runCatching {
            NotificationManagerCompat.from(context).cancel(callId.hashCode())
        }
    }

    private fun playCustomMessageSound(uriString: String) {
        runCatching {
            val uri = Uri.parse(uriString)
            val ringtone = RingtoneManager.getRingtone(context, uri) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                ringtone.audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            }
            ringtone.play()
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
