package com.mychattingpro.features.calls

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mychattingpro.MyChattingProApp

class IncomingCallActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_DECLINE_CALL = "com.mychattingpro.action.DECLINE_INCOMING_CALL"
        const val EXTRA_CALL_ID = "extra_call_id"
        const val EXTRA_CONVERSATION_ID = "extra_conversation_id"
        const val EXTRA_CALLER_ID = "extra_caller_id"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_DECLINE_CALL) return
        val callId = intent.getStringExtra(EXTRA_CALL_ID).orEmpty()
        val conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID).orEmpty()
        val callerId = intent.getStringExtra(EXTRA_CALLER_ID).orEmpty()
        if (callId.isBlank()) return

        val app = context.applicationContext as? MyChattingProApp ?: return
        app.container.ringtoneController.stopRinging(callId = callId, force = true)
        app.container.notificationManager.cancelCallNotification(callId)
        app.container.rejectIncomingCallFromAlert(
            callId = callId,
            conversationId = conversationId,
            callerId = callerId
        )
    }
}
