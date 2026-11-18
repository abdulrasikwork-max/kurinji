package com.safesphere.app.core.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.safesphere.app.R

/**
 * The app's notification channels, created once at app start (creating a channel needs no permission
 * and is a no-op when it already exists). 4A: ongoing tracking (SOS only since N6). 4B / N6: On my way reminders.
 */
object NotificationChannels {

    /** The ongoing notification shown while SafeSphere is tracking location for an emergency. */
    const val SAFETY_TRACKING = "safety_tracking"

    /** On my way's "There yet?" reminder and its one nudge (N6): heads-up, with sound, so they are noticed. */
    const val SAFETY_ALERTS = "safety_alerts"

    /**
     * Phase 17F's channel for the countdown of a shake gesture. Shake SOS left in N6 (D21), so the channel is no longer
     * created and is deleted once on phones that had it (otherwise the phone's settings would keep listing it).
     */
    private const val RETIRED_SOS_COUNTDOWN = "sos_countdown"

    /** N7 push channels (ids = core.push.PushChannel). */
    const val REPLIES = "irl_replies"
    const val PLANS = "irl_plans"
    const val PEOPLE = "irl_people"
    const val ACCOUNT = "irl_account"
    const val CIRCLE_SOS = "irl_circle_sos"
    const val STAFF = "irl_staff"

    /** Community staff (low): only on staff phones, created when the first staff push arrives. */
    fun createStaff(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(STAFF, context.getString(R.string.push_channel_staff_name),
            NotificationManager.IMPORTANCE_LOW).apply {
            description = context.getString(R.string.push_channel_staff_description)
        })
    }

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
        // N7 (D34, design 5.1): the push channels. Community staff is created only when a staff push first arrives.
        fun push(id: String, name: Int, description: Int, importance: Int) = manager.createNotificationChannel(
            NotificationChannel(id, context.getString(name), importance).apply {
                this.description = context.getString(description)
            })
        push(REPLIES, R.string.push_channel_replies_name, R.string.push_channel_replies_description,
            NotificationManager.IMPORTANCE_DEFAULT)
        push(PLANS, R.string.push_channel_plans_name, R.string.push_channel_plans_description,
            NotificationManager.IMPORTANCE_DEFAULT)
        push(PEOPLE, R.string.push_channel_people_name, R.string.push_channel_people_description,
            NotificationManager.IMPORTANCE_DEFAULT)
        push(ACCOUNT, R.string.push_channel_account_name, R.string.push_channel_account_description,
            NotificationManager.IMPORTANCE_DEFAULT)
        push(CIRCLE_SOS, R.string.push_channel_circle_sos_name, R.string.push_channel_circle_sos_description,
            NotificationManager.IMPORTANCE_HIGH)
        // A no-op when the channel does not exist (fresh installs).
        manager.deleteNotificationChannel(RETIRED_SOS_COUNTDOWN)
    }
}
