package com.example.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class NotificationActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_CONFIRM_CONTINUE = "com.example.action.CONFIRM_MANUAL_BRANCH"
        const val ACTION_ABORT_TASK = "com.example.action.ABORT_MANUAL_BRANCH"
        const val EXTRA_REQUEST_ID = "extra_request_id"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val reqId = intent?.getStringExtra(EXTRA_REQUEST_ID)
        when (intent?.action) {
            ACTION_CONFIRM_CONTINUE -> {
                ManualConfirmationBus.resolve(confirmed = true, requestId = reqId)
                TaskNotificationManager.cancelManualConfirmationNotification(context)
            }
            ACTION_ABORT_TASK -> {
                ManualConfirmationBus.resolve(confirmed = false, requestId = reqId)
                TaskNotificationManager.cancelManualConfirmationNotification(context)
            }
        }
    }
}
