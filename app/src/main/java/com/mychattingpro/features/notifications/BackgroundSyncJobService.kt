package com.mychattingpro.features.notifications

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.mychattingpro.MyChattingProApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
                if (container.tokenStore.getSession() != null) {
                    val prefs = container.prefsStore.current()
                    if (prefs.backgroundSyncEnabled && prefs.notificationsEnabled) {
                        RealtimeSyncService.start(applicationContext)
                    }
                    runCatching { container.messageRepository.retryAllPendingMessages() }
                    runCatching { container.chatRepository.refreshConversations() }
                    runCatching { container.authRepository.syncNotificationEndpoint() }
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
