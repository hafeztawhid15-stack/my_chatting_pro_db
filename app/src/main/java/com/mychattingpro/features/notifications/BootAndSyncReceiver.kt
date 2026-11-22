package com.mychattingpro.features.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mychattingpro.MyChattingProApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootAndSyncReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }
        val app = context.applicationContext as? MyChattingProApp ?: return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val container = app.container
                if (container.tokenStore.getSession() != null) {
                    val prefs = container.prefsStore.current()
                    BackgroundSyncJobService.schedule(context.applicationContext)
                    if (prefs.backgroundSyncEnabled && prefs.notificationsEnabled) {
                        RealtimeSyncService.start(context.applicationContext)
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
