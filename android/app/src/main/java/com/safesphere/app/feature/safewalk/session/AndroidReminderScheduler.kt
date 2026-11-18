package com.safesphere.app.feature.safewalk.session

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.time.Instant

/**
 * N6 (D22): the reminder and the nudge of "On my way" by `AlarmManager.setAndAllowWhileIdle` (RTC_WAKEUP). It needs no
 * permission (no exact-alarm permission, no boot receiver), so Android may deliver it a few minutes late, especially
 * in Doze; the screen says so. The alarm reaches only our own non-exported [OnMyWayAlarmReceiver]. A reboot loses the
 * alarms (no boot receiver, a documented limit): opening IRL schedules them again.
 */
class AndroidReminderScheduler(context: Context) : ReminderScheduler {

    private val appContext = context.applicationContext
    private val alarms = appContext.getSystemService(AlarmManager::class.java)

    override fun schedule(journeyId: Long, reminderAt: Instant, nudgeAt: Instant) {
        val manager = alarms ?: return
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminderAt.toEpochMilli(), intent(REMINDER, journeyId))
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nudgeAt.toEpochMilli(), intent(NUDGE, journeyId))
    }

    override fun cancel() {
        val manager = alarms ?: return
        manager.cancel(intent(REMINDER, 0))
        manager.cancel(intent(NUDGE, 0))
    }

    /** Same request code per kind: a new schedule replaces the old alarm (the extras are updated). */
    private fun intent(kind: Int, journeyId: Long): PendingIntent {
        val intent = Intent(appContext, OnMyWayAlarmReceiver::class.java)
            .setAction(OnMyWayAlarmReceiver.ACTION_ALARM)
            .putExtra(OnMyWayAlarmReceiver.EXTRA_JOURNEY_ID, journeyId)
        return PendingIntent.getBroadcast(appContext, kind, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private companion object {
        const val REMINDER = 6101
        const val NUDGE = 6102
    }
}
