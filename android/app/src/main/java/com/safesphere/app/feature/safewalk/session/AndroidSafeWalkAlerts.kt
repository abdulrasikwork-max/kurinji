package com.safesphere.app.feature.safewalk.session

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.safesphere.app.MainActivity
import com.safesphere.app.R
import com.safesphere.app.core.notifications.NotificationChannels
import com.safesphere.app.core.notifications.NotificationPermission
import com.safesphere.app.feature.emergency.entry.SosEntryActivity
import com.safesphere.app.feature.safewalk.domain.CheckInPhase

/**
 * "On my way" reminders (N6, D22) on the high-importance channel. "There yet?" has [I'm there] [More time]; the one
 * nudge 5 minutes later has [I'm there] [SOS] [Call 112] (at most three buttons fit; More time is inside IRL): SOS = the
 * same cancellable countdown, through the non-exported SosEntryActivity; Call 112 = the dialer (the person presses
 * call). Texts are generic (no label, no location) and the lock screen shows
 * only a neutral public version. Nothing here sends anything to anyone.
 */
class AndroidSafeWalkAlerts(context: Context) : SafeWalkAlerts {

    private val appContext = context.applicationContext

    override fun show(phase: CheckInPhase) {
        val nudge = when (phase) {
            CheckInPhase.MISSED -> false
            CheckInPhase.NO_RESPONSE -> true
            else -> return clear()
        }
        val title = if (nudge) R.string.omw_nudge_title else R.string.omw_reminder_title
        val text = if (nudge) R.string.omw_nudge_text else R.string.omw_reminder_text
        val builder = builder(title, text)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .addAction(R.drawable.ic_check_circle, appContext.getString(R.string.omw_action_there),
                actionIntent(CheckInActionReceiver.ACTION_THERE, 2))
        // Android shows at most three buttons on a notification (N6-C: a fourth, Call 112, was silently left out). The
        // nudge keeps I'm there, SOS and Call 112; More time stays one tap away inside IRL (the notification opens it).
        if (nudge) {
            builder.addAction(R.drawable.ic_stat_sos, appContext.getString(R.string.omw_action_sos), sosIntent())
            builder.addAction(R.drawable.ic_stat_sos, appContext.getString(R.string.omw_action_call), dialIntent())
        } else {
            builder.addAction(R.drawable.ic_history, appContext.getString(R.string.omw_action_more_time),
                actionIntent(CheckInActionReceiver.ACTION_MORE_TIME, 3))
        }
        notify(builder.build())
    }

    override fun clear() {
        NotificationManagerCompat.from(appContext).cancel(REMINDER_NOTIFICATION_ID)
    }

    private fun builder(title: Int, text: Int): NotificationCompat.Builder {
        val publicVersion = NotificationCompat.Builder(appContext, NotificationChannels.SAFETY_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_irl)
            .setContentTitle(appContext.getString(R.string.omw_public_title))
            .build()
        return NotificationCompat.Builder(appContext, NotificationChannels.SAFETY_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_irl)
            .setColor(androidx.core.content.ContextCompat.getColor(appContext, R.color.brand_cherry))
            .setContentTitle(appContext.getString(title))
            .setContentText(appContext.getString(text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(appContext.getString(text)))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
    }

    private fun notify(notification: android.app.Notification) {
        if (!NotificationPermission.isGranted(appContext)) return // "Before you need it" and the screen say so
        try {
            NotificationManagerCompat.from(appContext).notify(REMINDER_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // Permission revoked meanwhile; the in-app state still shows the reminder.
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
    private fun actionIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(appContext, CheckInActionReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(appContext, requestCode, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** The SOS shortcut's own path: the cancellable 5 s countdown, never an SOS by itself. */
    private fun sosIntent(): PendingIntent {
        val intent = Intent(appContext, SosEntryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(appContext, 4, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun dialIntent(): PendingIntent {
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + appContext.getString(R.string.emergency_number)))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(appContext, 5, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private companion object {
        const val REMINDER_NOTIFICATION_ID = 4101
    }
}
