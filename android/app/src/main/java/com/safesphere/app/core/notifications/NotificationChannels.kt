package com.safesphere.app.core.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.safesphere.app.R

/**
 * The app's notification channels, created once at app start (creating a channel needs no permission
 * and is a no-op when it already exists). 4A: ongoing tracking. 4B: Safe Walk check-in alerts.
 */
object NotificationChannels {

    /** The ongoing notification shown while SafeSphere is tracking location for an emergency. */
    const val SAFETY_TRACKING = "safety_tracking"

    /** Safe Walk check-in reminders and missed check-ins: heads-up, with sound, so they are noticed. */
    const val SAFETY_ALERTS = "safety_alerts"

    fun createAll(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val alerts = NotificationChannel(
            SAFETY_ALERTS,
            context.getString(R.string.notification_channel_alerts_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.notification_channel_alerts_description)
            enableVibration(true)
        }
        manager.createNotificationChannel(alerts)
        val tracking = NotificationChannel(
            SAFETY_TRACKING,
            context.getString(R.string.notification_channel_tracking_name),
            // LOW: always visible in the shade, but no sound every time the text is updated.
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.notification_channel_tracking_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(tracking)
    }
}
