package com.safesphere.app.feature.circlealert

import com.safesphere.app.core.location.GeoPoint
import com.safesphere.app.feature.circlealert.domain.CircleMessageKind
import com.safesphere.app.feature.circlealert.domain.CircleMessages
import com.safesphere.app.feature.safewalk.domain.ConcernKind
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 4D: the text a Safety Circle contact receives (the phone side of the SAFETY_CIRCLE allow-list). */
class CircleMessagesTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private val at = Instant.parse("2026-10-20T13:31:00Z") // 19:01 IST
    private val place = GeoPoint(12.9716, 77.5946)

    private fun compose(kind: CircleMessageKind, concern: ConcernKind? = null, location: GeoPoint? = place, automatic: Boolean = true) =
        CircleMessages.compose(kind, "Asha", at, zone, location, at, concern, automatic)

    @Test
    fun `SOS message - name, local time, what happened and a map link, nothing else`() {
        val text = compose(CircleMessageKind.SOS_STARTED)

        assertTrue(text.startsWith("Kurinji SOS: Asha started an emergency alert at 19:01, 20 Oct."))
        assertTrue(text.contains("https://www.google.com/maps/search/?api=1&query=12.97160,77.59460"))
        assertTrue(text.contains("call 112"))
        assertTrue(text.endsWith("(Sent automatically by Kurinji.)"))
    }

    @Test
    fun `a Safe Walk alert says it is not a confirmed emergency, and why it was sent`() {
        val text = compose(CircleMessageKind.WALK_NO_RESPONSE, ConcernKind.ROUTE_DEVIATION)

        assertTrue(text.contains("did not answer a Safe Walk safety check at 19:01, 20 Oct after leaving the planned route"))
        assertTrue(text.contains("This is not a confirmed emergency."))
        assertTrue(compose(CircleMessageKind.WALK_NO_RESPONSE, ConcernKind.UNEXPECTED_STOP).contains("after not moving for a while"))
    }

    @Test
    fun `without a location the message says so instead of inventing one`() {
        val text = compose(CircleMessageKind.SOS_STARTED, location = null)

        assertTrue(text.contains("Location is not available."))
        assertFalse(text.contains("maps"))
    }

    @Test
    fun `follow-ups - all clear, resolved, false alarm, location update`() {
        assertTrue(compose(CircleMessageKind.ALL_CLEAR).contains("responded at 19:01, 20 Oct and is OK"))
        assertTrue(compose(CircleMessageKind.SOS_RESOLVED).contains("marked themselves safe"))
        assertTrue(compose(CircleMessageKind.SOS_CANCELLED).contains("It was a false alarm."))
        assertTrue(compose(CircleMessageKind.SOS_LOCATION).contains("query=12.97160,77.59460"))
    }

    @Test
    fun `a message the person sends themselves does not claim to be automatic`() {
        assertFalse(compose(CircleMessageKind.SOS_STARTED, automatic = false).contains("automatically"))
    }

    @Test
    fun `allow-list - no accuracy, battery, coordinates beyond the link, or email ever appear`() {
        CircleMessageKind.entries.forEach { kind ->
            val text = compose(kind, ConcernKind.ROUTE_DEVIATION)
            listOf("accuracy", "battery", "%", "@", "destination", "±").forEach { forbidden ->
                assertFalse("$kind contains $forbidden", text.contains(forbidden, ignoreCase = true))
            }
        }
    }

    @Test
    fun `a blank or very long name is handled`() {
        assertTrue(CircleMessages.compose(CircleMessageKind.SOS_STARTED, "  ", at, zone, null, null, automatic = true)
            .contains("A Kurinji user started"))
        val long = CircleMessages.compose(CircleMessageKind.SOS_CANCELLED, "x".repeat(200), at, zone, null, null, automatic = false)
        assertEquals(40, long.substringAfter("Kurinji: ").substringBefore(" cancelled").length)
    }
}
