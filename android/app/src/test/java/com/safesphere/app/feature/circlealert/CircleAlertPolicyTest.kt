package com.safesphere.app.feature.circlealert

import com.safesphere.app.feature.circlealert.domain.AlertMemory
import com.safesphere.app.feature.circlealert.domain.AlertTrigger
import com.safesphere.app.feature.circlealert.domain.CircleAlertPolicy
import com.safesphere.app.feature.circlealert.domain.CircleMessageKind
import com.safesphere.app.feature.safewalk.domain.ConcernKind
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 4D: when the Safety Circle is told what (pure policy). */
class CircleAlertPolicyTest {

    private val policy = CircleAlertPolicy()
    private val t0 = Instant.parse("2026-10-20T18:00:00Z")

    private fun run(vararg steps: Pair<AlertTrigger, Instant>, start: AlertMemory = AlertMemory()): Pair<AlertMemory, List<CircleMessageKind>> {
        var memory = start
        val sent = mutableListOf<CircleMessageKind>()
        steps.forEach { (trigger, at) ->
            val decision = policy.decide(memory, trigger, at)
            memory = decision.memory
            sent += decision.send
        }
        return memory to sent
    }

    // ---- Safe Walk (level 2) ------------------------------------------------------------------

    @Test
    fun `escalation communication - level 2 on a walk texts the circle once per episode`() {
        val (_, sent) = run(
            AlertTrigger.WalkNoResponse(1, ConcernKind.UNEXPECTED_STOP) to t0,
            AlertTrigger.WalkNoResponse(1, null) to t0.plusSeconds(30) // same episode, e.g. a restart: no repeat
        )
        assertEquals(listOf(CircleMessageKind.WALK_NO_RESPONSE), sent)
    }

    @Test
    fun `I'm Safe after an alert sends one all clear, and nothing when no alert went out`() {
        val (_, sent) = run(
            AlertTrigger.WalkAnswered(1) to t0, // no alert before: nothing
            AlertTrigger.WalkNoResponse(1, null) to t0.plusSeconds(60),
            AlertTrigger.WalkAnswered(1) to t0.plusSeconds(120),
            AlertTrigger.WalkAnswered(1) to t0.plusSeconds(180)
        )
        assertEquals(listOf(CircleMessageKind.WALK_NO_RESPONSE, CircleMessageKind.ALL_CLEAR), sent)
    }

    @Test
    fun `a walk alerts at most three times, so the circle is never flooded`() {
        val steps = (0 until 5).flatMap { i ->
            listOf(
                AlertTrigger.WalkNoResponse(1, null) to t0.plusSeconds(i * 600L),
                AlertTrigger.WalkAnswered(1) to t0.plusSeconds(i * 600L + 60)
            )
        }.toTypedArray()
        val (_, sent) = run(*steps)
        assertEquals(3, sent.count { it == CircleMessageKind.WALK_NO_RESPONSE })
        assertEquals(3, sent.count { it == CircleMessageKind.ALL_CLEAR })
    }

    @Test
    fun `a walk that becomes an SOS gets no all clear - the SOS messages take over`() {
        val (_, sent) = run(
            AlertTrigger.WalkNoResponse(1, null) to t0,
            AlertTrigger.WalkBecameEmergency(1) to t0.plusSeconds(60),
            AlertTrigger.WalkAnswered(1) to t0.plusSeconds(120)
        )
        assertEquals(listOf(CircleMessageKind.WALK_NO_RESPONSE), sent)
    }

    // ---- SOS (level 3) ---------------------------------------------------------------------------

    @Test
    fun `SOS with a location is sent at once`() {
        val decision = policy.decide(AlertMemory(), AlertTrigger.EmergencyActive(7, t0, hasLocation = true), t0.plusSeconds(5))
        assertEquals(listOf(CircleMessageKind.SOS_STARTED), decision.send)
        assertNull(decision.reevaluateAt)
    }

    @Test
    fun `SOS waits up to 30 s for a first location, then goes without, and follows up once with the location`() {
        val waiting = policy.decide(AlertMemory(), AlertTrigger.EmergencyActive(7, t0, hasLocation = false), t0.plusSeconds(3))
        assertTrue(waiting.send.isEmpty())
        assertEquals(t0.plusSeconds(30), waiting.reevaluateAt)

        val (_, sent) = run(
            AlertTrigger.EmergencyActive(7, t0, hasLocation = false) to t0.plusSeconds(30),
            AlertTrigger.EmergencyActive(7, t0, hasLocation = true) to t0.plusSeconds(90),
            AlertTrigger.EmergencyActive(7, t0, hasLocation = true) to t0.plusSeconds(100),
            start = waiting.memory
        )
        assertEquals(listOf(CircleMessageKind.SOS_STARTED, CircleMessageKind.SOS_LOCATION), sent)
    }

    @Test
    fun `no waiting when no location can come (location off, no Play services)`() {
        val decision = policy.decide(
            AlertMemory(), AlertTrigger.EmergencyActive(7, t0, hasLocation = false, locationExpected = false), t0.plusSeconds(2)
        )
        assertEquals(listOf(CircleMessageKind.SOS_STARTED), decision.send)
    }

    @Test
    fun `the location follow-up is not sent after 10 minutes`() {
        val (_, sent) = run(
            AlertTrigger.EmergencyActive(7, t0, hasLocation = false, locationExpected = false) to t0,
            AlertTrigger.EmergencyActive(7, t0, hasLocation = true) to t0.plus(Duration.ofMinutes(11))
        )
        assertEquals(listOf(CircleMessageKind.SOS_STARTED), sent)
    }

    @Test
    fun `the end of an SOS is told once - resolved or false alarm`() {
        val (_, resolved) = run(
            AlertTrigger.EmergencyActive(7, t0, hasLocation = true) to t0,
            AlertTrigger.EmergencyEnded(7, resolved = true) to t0.plusSeconds(600),
            AlertTrigger.EmergencyEnded(7, resolved = true) to t0.plusSeconds(601)
        )
        assertEquals(listOf(CircleMessageKind.SOS_STARTED, CircleMessageKind.SOS_RESOLVED), resolved)

        val (_, cancelled) = run(
            AlertTrigger.EmergencyActive(8, t0, hasLocation = true) to t0,
            AlertTrigger.EmergencyEnded(8, resolved = false) to t0.plusSeconds(60)
        )
        assertEquals(CircleMessageKind.SOS_CANCELLED, cancelled.last())
    }

    @Test
    fun `an SOS cancelled before its first message went out tells nobody`() {
        val (_, sent) = run(
            AlertTrigger.EmergencyActive(7, t0, hasLocation = false) to t0.plusSeconds(2),
            AlertTrigger.EmergencyEnded(7, resolved = false) to t0.plusSeconds(10),
            AlertTrigger.EmergencyActive(7, t0, hasLocation = false) to t0.plusSeconds(40)
        )
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `an emergency first seen long after it started is never alerted automatically`() {
        val (_, sent) = run(
            AlertTrigger.EmergencyActive(7, t0, hasLocation = true) to t0.plus(Duration.ofMinutes(45)),
            AlertTrigger.EmergencyEnded(7, resolved = true) to t0.plus(Duration.ofMinutes(50))
        )
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `memory older than two days is forgotten`() {
        val (memory, _) = run(AlertTrigger.WalkNoResponse(1, null) to t0)
        val later = policy.decide(memory, AlertTrigger.WalkAnswered(2), t0.plus(Duration.ofDays(3)))
        assertTrue(later.memory.walks.isEmpty())
    }
}
