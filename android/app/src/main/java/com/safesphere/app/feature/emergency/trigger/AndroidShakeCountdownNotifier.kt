package com.safesphere.app.feature.emergency.trigger

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.safesphere.app.MainActivity
import com.safesphere.app.R
import com.safesphere.app.SafeSphereApp
import com.safesphere.app.core.notifications.NotificationChannels
import com.safesphere.app.core.notifications.NotificationPermission

/**
 * Phase 17F: the countdown of a shake that happened with IRL off the screen (during a Safe Walk). Its own high-importance
 * channel; the words say SOS/Emergency (U4: never colour alone); Cancel is a button on the notification itself, visible
 * on the lock screen too. Nothing in it reveals a place.
 */
class AndroidShakeCountdownNotifier(context: Context) : ShakeCountdownNotifier {

    private val appContext = context.applicationContext

    override fun showCountdown(secondsLeft: Int) {
        val cancel = PendingIntent.getBroadcast(
            appContext, 3,
            Intent(appContext, ShakeCountdownCancelReceiver::class.java).setAction(ShakeCountdownCancelReceiver.ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(appContext, NotificationChannels.SOS_COUNTDOWN)
            .setSmallIcon(R.drawable.ic_shield_alert)
            .setContentTitle(appContext.getString(R.string.shake_countdown_title, secondsLeft))
            .setContentText(appContext.getString(R.string.shake_countdown_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(appContext.getString(R.string.shake_countdown_text)))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .addAction(R.drawable.ic_close, appContext.getString(R.string.action_cancel_sos), cancel)
            .build()
        notify(notification)
    }

    override fun showStarted() {
        // PRIVATE with a neutral public version: "SOS started, your Safety Circle is texted" must never be readable on a
        // locked phone by whoever holds it (the same pattern as the tracking notification). The countdown above stays
        // public on purpose: Cancel has to work from the lock screen and it reveals only that a 5 s countdown is running.
        val publicVersion = NotificationCompat.Builder(appContext, NotificationChannels.SOS_COUNTDOWN)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(appContext.getString(R.string.tracking_notification_public_title))
            .build()
        val notification = NotificationCompat.Builder(appContext, NotificationChannels.SOS_COUNTDOWN)
            .setSmallIcon(R.drawable.ic_shield_alert)
            .setContentTitle(appContext.getString(R.string.shake_started_title))
            .setContentText(appContext.getString(R.string.shake_started_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(appContext.getString(R.string.shake_started_text)))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        notify(notification)
    }

    override fun clear() {
        NotificationManagerCompat.from(appContext).cancel(NOTIFICATION_ID)
    }

    private fun notify(notification: android.app.Notification) {
        if (!NotificationPermission.isGranted(appContext)) return
        try {
            NotificationManagerCompat.from(appContext).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // Permission revoked meanwhile; the shake does not listen in the background without it.
        }
    }

    private fun openApp(): PendingIntent {
        val intent = Intent(appContext, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return PendingIntent.getActivity(appContext, 4, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private companion object {
        const val NOTIFICATION_ID = 4103
    }
}

/** The Cancel button of the shake countdown. Not exported: only our own PendingIntent reaches it. */
class ShakeCountdownCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CANCEL) return
        (context.applicationContext as SafeSphereApp).container.shakeSos.cancelCountdown()
    }

    companion object {
        const val ACTION_CANCEL = "com.safesphere.app.action.SHAKE_SOS_CANCEL"
    }
}
