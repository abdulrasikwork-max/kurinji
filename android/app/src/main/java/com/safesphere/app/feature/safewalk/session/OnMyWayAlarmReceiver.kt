package com.safesphere.app.feature.safewalk.session

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.safesphere.app.SafeSphereApp

/**
 * N6 (D22): an "On my way" alarm went off. Not exported: only our own PendingIntent (AndroidReminderScheduler) reaches
 * it. The process may have been started just for this: the walk is recovered from the server or the phone's record,
 * and the reminder or the nudge is shown if the walk is still running. It contacts nobody.
 */
class OnMyWayAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ALARM) return
        val journeyId = intent.getLongExtra(EXTRA_JOURNEY_ID, -1)
        if (journeyId < 0) return
        val pending = goAsync()
        (context.applicationContext as SafeSphereApp).container.safeWalk.onAlarm(journeyId) { pending.finish() }
    }

    companion object {
        const val ACTION_ALARM = "com.safesphere.app.action.ON_MY_WAY_ALARM"
        const val EXTRA_JOURNEY_ID = "journeyId"
    }
}
