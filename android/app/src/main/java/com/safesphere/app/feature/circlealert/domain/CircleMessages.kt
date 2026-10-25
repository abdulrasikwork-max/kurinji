package com.safesphere.app.feature.circlealert.domain

import com.safesphere.app.core.location.GeoPoint
import com.safesphere.app.feature.safewalk.domain.ConcernKind
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The text a Safety Circle contact receives (Phase 4D). Pure Kotlin, unit-tested.
 *
 * This is the phone side of the Emergency Context Packet's SAFETY_CIRCLE projection, so it takes ONLY the allowed
 * fields as parameters: the person's name, the time, the type of alert, and a location link (with its time). It cannot
 * include a destination, accuracy, battery, history or other contacts, because it never receives them.
 *
 * Messages are plain English and short (one or two SMS parts). They say plainly what is and is not known: a Safe Walk
 * alert is "not a confirmed emergency"; an automatic message says it was sent automatically.
 */
object CircleMessages {

    private val TIME = DateTimeFormatter.ofPattern("HH:mm, d MMM", Locale.ENGLISH)

    fun compose(
        kind: CircleMessageKind,
        personName: String,
        at: Instant,
        zone: ZoneId,
        location: GeoPoint?,
        locationAt: Instant?,
        concern: ConcernKind? = null,
        automatic: Boolean
    ): String {
        val name = personName.trim().ifBlank { "A Kurinji user" }.take(MAX_NAME_LENGTH)
        val time = TIME.format(at.atZone(zone))
        val place = if (location != null) {
            val when_ = locationAt?.let { " (${TIME.format(it.atZone(zone))})" } ?: ""
            "Last known location$when_: ${mapLink(location)}"
        } else {
            "Location is not available."
        }
        val body = when (kind) {
            CircleMessageKind.WALK_NO_RESPONSE ->
                "Kurinji alert: $name did not answer a Safe Walk safety check at $time${reason(concern)}. " +
                    "This is not a confirmed emergency. $place Please try to call $name. " +
                    "If you cannot reach them and are worried, call 112."
            CircleMessageKind.SOS_STARTED ->
                "Kurinji SOS: $name started an emergency alert at $time. $place " +
                    "Please call $name now. If you cannot reach them, call 112."
            CircleMessageKind.SOS_LOCATION ->
                "Kurinji SOS update for $name. $place"
            CircleMessageKind.ALL_CLEAR ->
                "Kurinji: $name responded at $time and is OK. You can ignore the earlier Safe Walk alert."
            CircleMessageKind.SOS_RESOLVED ->
                "Kurinji: $name ended the SOS at $time and marked themselves safe."
            CircleMessageKind.SOS_CANCELLED ->
                "Kurinji: $name cancelled the SOS at $time. It was a false alarm."
        }
        return if (automatic) "$body (Sent automatically by Kurinji.)" else body
    }

    /** Google Maps URLs format: opens in any browser or maps app, no account needed. 5 decimals is about 1 m. */
    fun mapLink(point: GeoPoint): String =
        "https://www.google.com/maps/search/?api=1&query=" +
            String.format(Locale.ROOT, "%.5f,%.5f", point.latitude, point.longitude)

    private fun reason(concern: ConcernKind?): String = when (concern) {
        ConcernKind.ROUTE_DEVIATION -> " after leaving the planned route"
        ConcernKind.UNEXPECTED_STOP -> " after not moving for a while"
        null -> ""
    }

    private const val MAX_NAME_LENGTH = 40
}
