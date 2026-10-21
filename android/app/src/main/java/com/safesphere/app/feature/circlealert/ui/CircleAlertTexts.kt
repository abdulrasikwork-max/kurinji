package com.safesphere.app.feature.circlealert.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import com.safesphere.app.R
import com.safesphere.app.feature.circlealert.domain.AlertTarget
import com.safesphere.app.feature.circlealert.domain.CircleDelivery
import com.safesphere.app.feature.circlealert.domain.CircleMessageKind
import com.safesphere.app.feature.circlealert.domain.DeliveryStatus
import com.safesphere.app.feature.circlealert.session.CircleAlertState
import java.time.Instant

/** A per-contact status line: a text resource and, when it has one, the time to put in it. */
data class DeliveryLabel(@StringRes val text: Int, val at: Instant? = null)

/**
 * What one contact has been told about [target], in honest words. Pure mapping, unit-tested.
 * "Sent" always says delivery is not confirmed; "SMS app opened" always says we cannot know whether it was sent.
 */
fun deliveryLabelFor(state: CircleAlertState, target: AlertTarget, contactId: Long): DeliveryLabel {
    val latest = state.deliveriesFor(target).filter { it.contactId == contactId }.maxByOrNull { it.at }
        ?: return DeliveryLabel(if (state.autoActive) R.string.circle_status_will_text else R.string.circle_status_not_notified)
    return when (latest.status) {
        DeliveryStatus.SENDING -> DeliveryLabel(R.string.circle_status_sending)
        DeliveryStatus.SENT -> DeliveryLabel(R.string.circle_status_sent, latest.at)
        DeliveryStatus.DELIVERED -> DeliveryLabel(R.string.circle_status_delivered, latest.at)
        DeliveryStatus.FAILED -> DeliveryLabel(R.string.circle_status_failed, latest.at)
        DeliveryStatus.COMPOSER_OPENED -> DeliveryLabel(R.string.circle_status_composer, latest.at)
    }
}

/** One summary line for a whole target (Safe Walk card). Null when nothing was attempted. */
fun circleSummaryFor(state: CircleAlertState, target: AlertTarget): CircleSummary? {
    val latestPerContact = state.deliveriesFor(target).groupBy { it.contactId }.values.mapNotNull { list -> list.maxByOrNull(CircleDelivery::at) }
    if (latestPerContact.isEmpty()) return null
    return CircleSummary(
        sent = latestPerContact.count { it.status == DeliveryStatus.SENT || it.status == DeliveryStatus.DELIVERED },
        delivered = latestPerContact.count { it.status == DeliveryStatus.DELIVERED },
        failed = latestPerContact.count { it.status == DeliveryStatus.FAILED },
        composer = latestPerContact.count { it.status == DeliveryStatus.COMPOSER_OPENED },
        sending = latestPerContact.count { it.status == DeliveryStatus.SENDING }
    )
}

data class CircleSummary(val sent: Int, val delivered: Int, val failed: Int, val composer: Int, val sending: Int)

/** A summary line with an optional count (e.g. how many contacts were texted). */
data class SummaryText(@StringRes val text: Int, val count: Int = 0)

/**
 * The Safety Circle line under an active Safe Walk. Pure, unit-tested. [alerting] = a check-in prompt is open
 * (level 1 or 2), when it matters most whether anyone will be told.
 */
fun walkCircleLineFor(state: CircleAlertState, target: AlertTarget, alerting: Boolean): SummaryText? {
    val latest = state.deliveriesFor(target).maxByOrNull(CircleDelivery::at)
    val summary = circleSummaryFor(state, target)
    return when {
        latest?.kind == CircleMessageKind.ALL_CLEAR && latest.status != DeliveryStatus.FAILED ->
            SummaryText(R.string.circle_walk_all_clear)
        summary != null && summary.sending > 0 -> SummaryText(R.string.circle_walk_sending)
        summary != null && summary.sent > 0 && summary.delivered == summary.sent ->
            SummaryText(R.string.circle_walk_delivered, summary.delivered)
        summary != null && summary.sent > 0 -> SummaryText(R.string.circle_walk_sent, summary.sent)
        summary != null && summary.failed > 0 -> SummaryText(R.string.circle_walk_failed, summary.failed)
        summary != null && summary.composer > 0 -> SummaryText(R.string.circle_walk_composer)
        !alerting -> null
        state.autoActive -> SummaryText(R.string.circle_walk_will_text)
        else -> SummaryText(R.string.circle_walk_not_notified)
    }
}

/** Opens the phone's SMS app with the message for one contact. The PERSON decides whether to send it. */
fun openSmsComposer(context: Context, phoneNumber: String, text: String): Boolean {
    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(phoneNumber)))
        .putExtra("sms_body", text)
    return try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}

/** Opens the dialer with the emergency number filled in. Needs no permission; the PERSON presses call. */
fun openEmergencyDialer(context: Context): Boolean {
    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + context.getString(R.string.emergency_number)))
    return try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}
