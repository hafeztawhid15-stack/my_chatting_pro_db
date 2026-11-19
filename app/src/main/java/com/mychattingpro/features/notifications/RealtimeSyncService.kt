package com.mychattingpro.features.notifications

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.R
import com.mychattingpro.MyChattingProApp

/**
 * Optional user-enabled ForegroundService (dataSync) maintaining an authenticated
 * Cloudflare Durable Object WebSocket without Firebase Cloud Messaging.
 */
class RealtimeSyncService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as MyChattingProApp
        app.container.notificationManager.createChannels()

        val notification = NotificationCompat.Builder(this, AppNotificationManager.CHANNEL_SYNC)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_sync_running))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(1042, notification)
        app.container.networkModule.realtimeSocketManager.start()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, RealtimeSyncService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RealtimeSyncService::class.java))
        }
    }
}
