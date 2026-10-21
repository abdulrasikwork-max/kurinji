package com.safesphere.app.feature.safewalk.session

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.safesphere.app.SafeSphereApp

/**
 * Handles the "I'm OK" button of a check-in notification. Not exported: only our own PendingIntent reaches it.
 * The notification can outlive the process, so the walk may first have to be recovered; goAsync() keeps the
 * process alive until that is done.
 */
class CheckInActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CHECK_IN) return
        val pending = goAsync()
        (context.applicationContext as SafeSphereApp).container.safeWalk.checkInFromNotification { pending.finish() }
    }

    companion object {
        const val ACTION_CHECK_IN = "com.safesphere.app.action.SAFE_WALK_CHECK_IN"
    }
}
