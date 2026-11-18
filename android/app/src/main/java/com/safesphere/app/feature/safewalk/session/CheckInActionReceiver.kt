package com.safesphere.app.feature.safewalk.session

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.safesphere.app.SafeSphereApp

/**
 * The "I'm there" and "More time" buttons of an "On my way" reminder (N6, D22). Not exported: only our own
 * PendingIntent reaches it. The notification can outlive the process, so the walk may first have to be recovered;
 * goAsync() keeps the process alive until that is done.
 */
class CheckInActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val walk = (context.applicationContext as SafeSphereApp).container.safeWalk
        when (intent.action) {
            ACTION_MORE_TIME -> goAsync().let { pending -> walk.checkInFromNotification { pending.finish() } }
            ACTION_THERE -> goAsync().let { pending -> walk.completeFromNotification { pending.finish() } }
        }
    }

    companion object {
        const val ACTION_MORE_TIME = "com.safesphere.app.action.ON_MY_WAY_MORE_TIME"
        const val ACTION_THERE = "com.safesphere.app.action.ON_MY_WAY_THERE"
    }
}
