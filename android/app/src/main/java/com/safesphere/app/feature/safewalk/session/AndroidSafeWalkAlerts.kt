package com.safesphere.app.feature.safewalk.session

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.safesphere.app.MainActivity
import com.safesphere.app.R
import com.safesphere.app.core.notifications.NotificationChannels
import com.safesphere.app.core.notifications.NotificationPermission
import com.safesphere.app.feature.safewalk.domain.CheckInPhase
import com.safesphere.app.feature.safewalk.domain.CheckInStatus
import com.safesphere.app.feature.safewalk.domain.ConcernKind

/**
 * Safe Walk notifications on the high-importance channel. Each check-in alert has an "I'm OK" action, so the
 * person can check in without opening the app. Texts are generic (no destination, no location) and the lock
 * screen shows only a neutral public version.
 */
class AndroidSafeWalkAlerts(context: Context) : SafeWalkAlerts {

    private val appContext = context.applicationContext

    override fun show(status: CheckInStatus) {
        val (title, text) = when (status.phase) {
            CheckInPhase.ON_TRACK -> return clear()
            CheckInPhase.DUE_SOON -> R.string.safe_walk_alert_due_soon_title to R.string.safe_walk_alert_due_soon_text
            CheckInPhase.MISSED -> when (status.concern) {
                ConcernKind.ROUTE_DEVIATION -> R.string.safe_walk_alert_concern_title to R.string.safe_walk_alert_off_route_text
                ConcernKind.UNEXPECTED_STOP -> R.string.safe_walk_alert_concern_title to R.string.safe_walk_alert_stopped_text
                null -> R.string.safe_walk_alert_missed_title to R.string.safe_walk_alert_missed_text
            }
            CheckInPhase.NO_RESPONSE -> R.string.safe_walk_alert_no_response_title to R.string.safe_walk_alert_no_response_text
        }
        val notification = builder(title, text)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .addAction(R.drawable.ic_check_circle, appContext.getString(R.string.action_im_ok), checkInIntent())
            // A missed check-in must not be silently swiped away.
            .setOngoing(status.phase != CheckInPhase.DUE_SOON)
            .build()
        notify(CHECK_IN_NOTIFICATION_ID, notification)
    }

    override fun clear() {
        NotificationManagerCompat.from(appContext).cancel(CHECK_IN_NOTIFICATION_ID)
    }

    override fun showBatteryCritical() {
        val notification = builder(R.string.safe_walk_alert_battery_title, R.string.safe_walk_alert_battery_text)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOnlyAlertOnce(true)
            .build()
        notify(BATTERY_NOTIFICATION_ID, notification)
    }

    override fun clearBatteryCritical() {
        NotificationManagerCompat.from(appContext).cancel(BATTERY_NOTIFICATION_ID)
    }

    private fun builder(title: Int, text: Int): NotificationCompat.Builder {
        val publicVersion = NotificationCompat.Builder(appContext, NotificationChannels.SAFETY_ALERTS)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(appContext.getString(R.string.safe_walk_alert_public_title))
            .build()
        return NotificationCompat.Builder(appContext, NotificationChannels.SAFETY_ALERTS)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(appContext.getString(title))
            .setContentText(appContext.getString(text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(appContext.getString(text)))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setContentIntent(openAppIntent())
            .setAutoCancel(false)
    }

    private fun notify(id: Int, notification: android.app.Notification) {
        if (!NotificationPermission.isGranted(appContext)) return // the screen warns about this
        try {
            NotificationManagerCompat.from(appContext).notify(id, notification)
        } catch (e: SecurityException) {
            // Permission revoked meanwhile; the in-app banner still shows the state.
        }
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(appContext, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return PendingIntent.getActivity(appContext, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Explicit intent to our own non-exported receiver: no other app can trigger or intercept it. */
    private fun checkInIntent(): PendingIntent {
        val intent = Intent(appContext, CheckInActionReceiver::class.java).setAction(CheckInActionReceiver.ACTION_CHECK_IN)
        return PendingIntent.getBroadcast(appContext, 2, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private companion object {
        const val CHECK_IN_NOTIFICATION_ID = 4101
        const val BATTERY_NOTIFICATION_ID = 4102
    }
}
