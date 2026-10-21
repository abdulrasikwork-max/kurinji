package com.safesphere.app.core.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.safesphere.app.SafeSphereApp

/**
 * Receives "sent" and "delivered" results for SMS the app sent itself (Phase 4D). Not exported: only the explicit
 * PendingIntents created by [AndroidSmsSender] reach it.
 */
class SmsResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID) ?: return
        val app = context.applicationContext as? SafeSphereApp ?: return
        app.container.smsSender.onPartResult(action, requestId, resultCode, intent)
    }

    companion object {
        const val ACTION_SENT = "com.safesphere.app.action.SMS_SENT"
        const val ACTION_DELIVERED = "com.safesphere.app.action.SMS_DELIVERED"
        const val EXTRA_REQUEST_ID = "request_id"
    }
}
