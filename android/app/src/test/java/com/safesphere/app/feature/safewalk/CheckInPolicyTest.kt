package com.safesphere.app.feature.safewalk

import com.safesphere.app.feature.safewalk.domain.CheckInPhase
import com.safesphere.app.feature.safewalk.domain.CheckInPolicy
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** N6 (D22): the reminder schedule of "On my way" is pure: before the time, "There yet?", one nudge, then quiet. */
class CheckInPolicyTest {

    private val policy = CheckInPolicy()
    private val due = Instant.parse("2026-10-20T18:20:00Z")

    @Test
    fun `before the time nothing, at the time There yet, 5 minutes later the nudge, then never anything again`() {
        assertEquals(CheckInPhase.ON_TRACK, policy.evaluate(due, due.minusSeconds(1)).phase)
        assertEquals(due, policy.evaluate(due, due.minusSeconds(60)).nextChangeAt)
        assertEquals(CheckInPhase.MISSED, policy.evaluate(due, due).phase)
        assertEquals(due.plus(Duration.ofMinutes(5)), policy.evaluate(due, due).nextChangeAt)
        assertEquals(CheckInPhase.NO_RESPONSE, policy.evaluate(due, due.plus(Duration.ofMinutes(5))).phase)
        val later = policy.evaluate(due, due.plus(Duration.ofHours(3)))
        assertEquals(CheckInPhase.NO_RESPONSE, later.phase)
        assertNull("no third reminder", later.nextChangeAt)
    }

    @Test
    fun `the alarms are the reminder and the nudge, and the choices are the design's`() {
        assertEquals(listOf(due, due.plus(Duration.ofMinutes(5))), policy.alarms(due))
        assertEquals(listOf(10, 20, 30, 45, 60), CheckInPolicy.CHOICES)
    }

    @Test
    fun `the reminder follows the server's time left, not the phone's clock`() {
        val serverTime = Instant.parse("2026-10-20T18:00:00Z")
        val phoneNow = serverTime.minusSeconds(75) // the phone's clock is 75 s behind
        assertEquals(phoneNow.plus(Duration.ofMinutes(20)), CheckInPolicy.localDeadline(due, serverTime, phoneNow))
    }
}
