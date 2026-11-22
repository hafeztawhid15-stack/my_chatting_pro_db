package com.mychattingpro.features.notifications

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.R
import com.mychattingpro.MyChattingProApp
import com.mychattingpro.core.security.SafeLogger

/**
 * Optional user-enabled ForegroundService (dataSync) maintaining an authenticated
 * Cloudflare Durable Object WebSocket without Firebase Cloud Messaging.
 */
class RealtimeSyncService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as? MyChattingProApp ?: run {
            stopSelf()
            return START_NOT_STICKY
        }
        val started = runCatching {
            app.container.notificationManager.createChannels()

            val notification = NotificationCompat.Builder(this, AppNotificationManager.CHANNEL_SYNC)
                .setSmallIcon(R.drawable.ic_notif_chat)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.notif_sync_running))
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

            val fgType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }
            ServiceCompat.startForeground(this, 1042, notification, fgType)
        }.onFailure { err ->
            SafeLogger.w("RealtimeSyncService", "Failed to enter foreground: ${err.javaClass.simpleName}")
            stopSelf()
        }.isSuccess

        if (!started) {
            return START_NOT_STICKY
        }

        app.container.networkModule.realtimeSocketManager.start()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun start(context: Context) {
            runCatching {
                val intent = Intent(context, RealtimeSyncService::class.java)
                ContextCompat.startForegroundService(context, intent)
            }.onFailure { err ->
                SafeLogger.w("RealtimeSyncService", "Cannot start foreground service: ${err.javaClass.simpleName}")
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, RealtimeSyncService::class.java))
            }
        }
    }
}
