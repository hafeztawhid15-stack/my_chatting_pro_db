package com.mychattingpro.features.notifications

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.example.R
import com.mychattingpro.MyChattingProApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Native JobScheduler periodic sync & offline retry worker without external background libraries or Firebase.
 */
class BackgroundSyncJobService : JobService() {

    private var jobScope: CoroutineScope? = null

    override fun onStartJob(params: JobParameters?): Boolean {
        val app = application as? MyChattingProApp ?: return false
        jobScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        jobScope?.launch {
            try {
                val container = app.container
                val session = container.tokenStore.currentSessionOrNull() ?: container.tokenStore.getSession()
                if (session != null) {
                    val prefs = container.prefsStore.current()
                    if (prefs.backgroundSyncEnabled && prefs.notificationsEnabled) {
                        RealtimeSyncService.start(applicationContext)
                    }
                    runCatching { container.messageRepository.retryAllPendingMessages() }
                    val refreshed = runCatching { container.chatRepository.refreshConversations() }.isSuccess
                    runCatching { container.authRepository.syncNotificationEndpoint() }

                    // Notify missed unread messages if background sync caught messages while WebSocket was dormant
                    if (refreshed && prefs.notificationsEnabled && !container.isAppInForeground) {
                        runCatching {
                            val conversations = container.database.conversationDao().observeConversations().first()
                            val now = System.currentTimeMillis()
                            conversations.forEach { conv ->
                                val isMuted = conv.mutedUntilMs != null && conv.mutedUntilMs > now
                                val lastMsgId = conv.lastMessageId
                                if (!conv.isBlockedByMe &&
                                    !conv.hasBlockedMe &&
                                    !isMuted &&
                                    conv.unreadCount > 0 &&
                                    lastMsgId != null &&
                                    conv.lastMessageSenderId != session.userId &&
                                    conv.id != container.activeConversationId
                                ) {
                                    val preview = conv.lastMessagePreview?.takeIf { it.isNotBlank() }
                                        ?: when (conv.lastMessageType?.lowercase()) {
                                            "image" -> applicationContext.getString(R.string.reply_quoted_photo)
                                            "video" -> applicationContext.getString(R.string.reply_quoted_video)
                                            "voice" -> applicationContext.getString(R.string.reply_quoted_voice)
                                            "audio" -> applicationContext.getString(R.string.reply_quoted_audio)
                                            "file" -> applicationContext.getString(R.string.reply_quoted_file)
                                            else -> applicationContext.getString(R.string.notif_new_message_body)
                                        }
                                    container.notificationManager.showNewMessageNotification(
                                        senderName = conv.peerDisplayName.ifBlank { applicationContext.getString(R.string.app_name) },
                                        conversationId = conv.id,
                                        previewText = preview,
                                        prefs = prefs,
                                        mutedUntilMs = conv.mutedUntilMs,
                                        customSoundUri = conv.customSoundUri,
                                        eventId = lastMsgId,
                                        isAppForeground = false
                                    )
                                }
                            }
                        }
                    }
                }
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        jobScope?.cancel()
        jobScope = null
        return true
    }

    companion object {
        private const val JOB_ID = 4401

        fun schedule(context: Context) {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
            val existing = scheduler.getPendingJob(JOB_ID)
            if (existing != null) return
            val component = ComponentName(context, BackgroundSyncJobService::class.java)
            val info = JobInfo.Builder(JOB_ID, component)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(15 * 60 * 1000L)
                .setPersisted(true)
                .build()
            runCatching { scheduler.schedule(info) }
        }

        fun cancel(context: Context) {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
            runCatching { scheduler.cancel(JOB_ID) }
        }
    }
}
