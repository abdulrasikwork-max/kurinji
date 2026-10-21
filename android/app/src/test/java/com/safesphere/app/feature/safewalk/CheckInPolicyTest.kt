package com.safesphere.app.feature.safewalk

import com.safesphere.app.core.escalation.EscalationLevel
import com.safesphere.app.feature.safewalk.domain.CheckInPhase
import com.safesphere.app.feature.safewalk.domain.CheckInPolicy
import com.safesphere.app.feature.safewalk.domain.Concern
import com.safesphere.app.feature.safewalk.domain.ConcernKind
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The pure check-in / escalation policy (Phase 4B). */
class CheckInPolicyTest {

    private val policy = CheckInPolicy(warningLead = Duration.ofSeconds(60), responseWindow = Duration.ofMinutes(2))
    private val due = Instant.parse("2026-10-20T18:10:00Z")

    @Test
    fun `before the warning the walk is on track at level 0`() {
        val status = policy.evaluate(due, due.minusSeconds(61))
        assertEquals(CheckInPhase.ON_TRACK, status.phase)
        assertEquals(EscalationLevel.NORMAL, status.level)
        assertEquals(due.minusSeconds(60), status.nextChangeAt)
    }

    @Test
    fun `warning - the last minute before the deadline is due soon, still level 0`() {
        val status = policy.evaluate(due, due.minusSeconds(60))
        assertEquals(CheckInPhase.DUE_SOON, status.phase)
        assertEquals(EscalationLevel.NORMAL, status.level)
        assertEquals(due, status.nextChangeAt)
    }

    @Test
    fun `check-in missed - at the deadline the level becomes 1`() {
        val status = policy.evaluate(due, due)
        assertEquals(CheckInPhase.MISSED, status.phase)
        assertEquals(EscalationLevel.CHECK_IN_MISSED, status.level)
        assertEquals(due.plus(Duration.ofMinutes(2)), status.nextChangeAt)
    }

    @Test
    fun `escalation - no response within the window is level 2`() {
        val status = policy.evaluate(due, due.plus(Duration.ofMinutes(2)))
        assertEquals(CheckInPhase.NO_RESPONSE, status.phase)
        assertEquals(EscalationLevel.NO_RESPONSE, status.level)
        assertNull(status.nextChangeAt)
    }

    @Test
    fun `the automatic ladder never goes above level 2, however long it waits`() {
        val status = policy.evaluate(due, due.plus(Duration.ofDays(3)))
        assertEquals(EscalationLevel.NO_RESPONSE, status.level)
    }

    // ---- Phase 4C: a movement concern opens the prompt early -------------------------------------

    @Test
    fun `a concern before the deadline is level 1 at once, with its reason`() {
        val raised = due.minus(Duration.ofMinutes(7))
        val status = policy.evaluate(due, raised.plusSeconds(10), Concern(ConcernKind.UNEXPECTED_STOP, raised))

        assertEquals(CheckInPhase.MISSED, status.phase)
        assertEquals(EscalationLevel.CHECK_IN_MISSED, status.level)
        assertEquals(ConcernKind.UNEXPECTED_STOP, status.concern)
        assertEquals(raised.plus(Duration.ofMinutes(2)), status.nextChangeAt)
    }

    @Test
    fun `an unanswered concern reaches level 2 after the same response window, and never more`() {
        val raised = due.minus(Duration.ofMinutes(7))
        val concern = Concern(ConcernKind.ROUTE_DEVIATION, raised)

        val noResponse = policy.evaluate(due, raised.plus(Duration.ofMinutes(2)), concern)
        assertEquals(CheckInPhase.NO_RESPONSE, noResponse.phase)
        assertEquals(ConcernKind.ROUTE_DEVIATION, noResponse.concern)
        assertEquals(EscalationLevel.NO_RESPONSE, policy.evaluate(due, due.plus(Duration.ofHours(5)), concern).level)
    }

    @Test
    fun `a concern from the future (clock changed) or after the deadline does not change the check-in rules`() {
        val now = due.minus(Duration.ofMinutes(5))
        assertEquals(CheckInPhase.ON_TRACK, policy.evaluate(due, now, Concern(ConcernKind.UNEXPECTED_STOP, now.plusSeconds(60))).phase)
        val late = policy.evaluate(due, due.plusSeconds(30), Concern(ConcernKind.UNEXPECTED_STOP, due.plusSeconds(10)))
        assertNull(late.concern)
        assertEquals(CheckInPhase.MISSED, late.phase)
    }

    @Test
    fun `the server's deadline is converted using the time left, so a wrong phone clock does not matter`() {
        val serverTime = Instant.parse("2026-10-20T18:00:00Z")
        val serverDue = serverTime.plus(Duration.ofMinutes(10))
        val phoneNow = serverTime.minus(Duration.ofMinutes(37)) // phone clock 37 minutes behind

        val local = CheckInPolicy.localDeadline(serverDue, serverTime, phoneNow)

        assertEquals(phoneNow.plus(Duration.ofMinutes(10)), local)
    }
}
