package com.safesphere.app.core.push

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.safesphere.app.R
import com.safesphere.app.core.notifications.NotificationPermission

/**
 * N7 (D32, D34): draws IRL's push notifications. The IRL mark for everything except an SOS from someone's Circle (the
 * siren, emergency red: bright red means only emergency). One id per thread (a newer push replaces the older one,
 * alerting once); one group per channel with a summary of names, never a count; a neutral lock-screen version without
 * names; social ones vanish after a day. Every notification carries the tag [TAG], so a sign-out removes exactly these
 * and never the SOS service's own.
 */
class AndroidPushTray(private val context: Context) : PushTray {

    private val manager = NotificationManagerCompat.from(context)

    override fun show(kind: PushKind, notice: PushNotice, silent: Boolean) {
        if (!NotificationPermission.isGranted(context)) return
        val id = PushRules.notificationId(notice.thread)
        val builder = base(kind, silent)
            .setContentTitle(notice.title)
            .setContentText(notice.text)
            .setStyle(notice.text?.let { NotificationCompat.BigTextStyle().bigText(it) })
            .setContentIntent(open(notice.target, id))
            .setPublicVersion(base(kind, true).setContentTitle(notice.publicTitle).build())
        for ((i, action) in notice.actions.withIndex()) {
            val dial = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", action.dialNumber, null))
            builder.addAction(0, action.label, PendingIntent.getActivity(context, id * 4 + i + 1, dial,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        post(id, builder)
        summarise(kind.channel)
    }

    override fun showGeneric(kind: PushKind, silent: Boolean) {
        if (!NotificationPermission.isGranted(context)) return
        val title = context.getString(genericLine(kind.channel))
        val thread = "generic-" + kind.channel.name
        val id = PushRules.notificationId(thread)
        val builder = base(kind, silent)
            .setContentTitle(title)
            .setContentIntent(open(genericTarget(kind), id))
            .setPublicVersion(base(kind, true).setContentTitle(title).build())
        post(id, builder)
    }

    override fun cancel(thread: String) {
        manager.cancel(TAG, PushRules.notificationId(thread))
    }

    override fun cancelAll() {
        val system = context.getSystemService(NotificationManager::class.java) ?: return
        system.activeNotifications.filter { it.tag == TAG || it.tag == SUMMARY_TAG }
            .forEach { manager.cancel(it.tag, it.id) }
    }

    private fun base(kind: PushKind, silent: Boolean): NotificationCompat.Builder {
        if (kind == PushKind.STAFF_WAITING) com.safesphere.app.core.notifications.NotificationChannels.createStaff(context)
        val sos = kind == PushKind.CIRCLE_SOS
        return NotificationCompat.Builder(context, kind.channel.id)
            .setSmallIcon(if (sos) R.drawable.ic_stat_sos else R.drawable.ic_stat_irl)
            .setColor(ContextCompat.getColor(context, if (sos) R.color.ss_emergency else R.color.brand_cherry))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setSilent(silent)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setGroup(kind.channel.id)
            .setPriority(if (sos || kind == PushKind.OMW_REMINDER) NotificationCompat.PRIORITY_HIGH
                else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(when {
                sos -> NotificationCompat.CATEGORY_ALARM
                kind == PushKind.OMW_REMINDER -> NotificationCompat.CATEGORY_REMINDER
                kind.social -> NotificationCompat.CATEGORY_SOCIAL
                else -> NotificationCompat.CATEGORY_STATUS
            })
            .apply { if (kind.social) setTimeoutAfter(PushRules.SOCIAL_TIMEOUT_MS) }
    }

    /** A summary per channel listing the titles (names, never a count). Shown by Android when 2 or more are grouped. */
    private fun summarise(channel: PushChannel) {
        val system = context.getSystemService(NotificationManager::class.java) ?: return
        val titles = system.activeNotifications
            .filter { it.tag == TAG && it.notification.group == channel.id }
            .mapNotNull { it.notification.extras.getCharSequence(NotificationCompat.EXTRA_TITLE) }
        if (titles.size < 2) return
        val style = NotificationCompat.InboxStyle()
        titles.take(5).forEach(style::addLine)
        val builder = NotificationCompat.Builder(context, channel.id)
            .setSmallIcon(R.drawable.ic_stat_irl)
            .setColor(ContextCompat.getColor(context, R.color.brand_cherry))
            .setStyle(style)
            .setGroup(channel.id)
            .setGroupSummary(true)
            .setSilent(true)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(NotificationCompat.Builder(context, channel.id)
                .setSmallIcon(R.drawable.ic_stat_irl)
                .setContentTitle(context.getString(genericLine(channel))).build())
        try {
            manager.notify(SUMMARY_TAG, PushRules.notificationId("summary-" + channel.name), builder.build())
        } catch (e: SecurityException) {
            // The permission was taken away a moment ago: nothing to show.
        }
    }

    private fun post(id: Int, builder: NotificationCompat.Builder) {
        try {
            manager.notify(TAG, id, builder.build())
        } catch (e: SecurityException) {
            // The permission was taken away a moment ago: nothing to show.
        }
    }

    private fun open(target: PushTarget, requestCode: Int): PendingIntent {
        val intent = Intent(context, NotificationRouterActivity::class.java)
            .putExtra(PushTarget.EXTRA_TARGET, target.name)
            .apply { target.id?.let { putExtra(PushTarget.EXTRA_ID, it) } }
        return PendingIntent.getActivity(context, requestCode, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        const val TAG = "irl_push"
        private const val SUMMARY_TAG = "irl_push_summary"

        fun genericLine(channel: PushChannel): Int = when (channel) {
            PushChannel.REPLIES, PushChannel.PLANS -> R.string.push_generic_replies
            PushChannel.PEOPLE -> R.string.push_generic_people
            PushChannel.ACCOUNT -> R.string.push_generic_account
            PushChannel.CIRCLE_SOS -> R.string.push_public_circle_sos
            PushChannel.ON_MY_WAY -> R.string.push_generic_on_my_way
            PushChannel.STAFF -> R.string.push_staff_waiting
        }

        fun genericTarget(kind: PushKind): PushTarget = when (kind.channel) {
            PushChannel.REPLIES, PushChannel.PLANS -> PushTarget.Replies
            PushChannel.PEOPLE -> if (kind == PushKind.CIRCLE_REQUEST) PushTarget.CircleRequests else PushTarget.Requests
            PushChannel.ACCOUNT -> PushTarget.You
            PushChannel.CIRCLE_SOS -> PushTarget.CircleRequests
            PushChannel.ON_MY_WAY -> PushTarget.OnMyWay
            PushChannel.STAFF -> PushTarget.Staff
        }
    }
}
