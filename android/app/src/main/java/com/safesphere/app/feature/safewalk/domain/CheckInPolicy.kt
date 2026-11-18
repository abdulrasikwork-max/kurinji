package com.safesphere.app.feature.safewalk.domain

import java.time.Duration
import java.time.Instant

/**
 * Where an "On my way" reminder stands (N6, D22). The names stay from Phase 4B; the meaning is a reminder now, never an
 * escalation: nothing is sent to anyone at any phase.
 */
enum class CheckInPhase {
    /** Before the chosen time. */
    ON_TRACK,

    /** Kept for old code paths; the N6 policy never returns it (no warning before the time). */
    DUE_SOON,

    /** The time has come: "There yet?" [I'm there] [More time]. */
    MISSED,

    /** 5 minutes later, still no answer: ONE more nudge with SOS / Call 112, then quiet. */
    NO_RESPONSE
}

data class CheckInStatus(
    val phase: CheckInPhase,
    val dueAt: Instant,
    /** When the phase will change next if nothing happens; null once the nudge was due (then IRL stays quiet). */
    val nextChangeAt: Instant?
)

/**
 * The reminder schedule of "On my way" (N6, D22). Pure Kotlin and stateless (a function of the time chosen and the
 * clock), so it survives restarts and is unit-tested. At [dueAt]: "There yet?"; [nudgeAfter] later: one more nudge;
 * then nothing more. It never contacts anyone and never starts an SOS.
 */
class CheckInPolicy(private val nudgeAfter: Duration = NUDGE_AFTER) {

    fun evaluate(dueAt: Instant, now: Instant): CheckInStatus {
        val nudgeAt = nudgeAt(dueAt)
        return when {
            now.isBefore(dueAt) -> CheckInStatus(CheckInPhase.ON_TRACK, dueAt, dueAt)
            now.isBefore(nudgeAt) -> CheckInStatus(CheckInPhase.MISSED, dueAt, nudgeAt)
            else -> CheckInStatus(CheckInPhase.NO_RESPONSE, dueAt, null)
        }
    }

    /** The two moments the phone's alarm wakes it for: the reminder and the one nudge. */
    fun alarms(dueAt: Instant): List<Instant> = listOf(dueAt, nudgeAt(dueAt))

    fun nudgeAt(dueAt: Instant): Instant = dueAt.plus(nudgeAfter)

    companion object {
        val NUDGE_AFTER: Duration = Duration.ofMinutes(5)

        /** The choices on the screen (minutes). The server accepts 2 to 60. */
        val CHOICES = listOf(10, 20, 30, 45, 60)

        /**
         * Converts the server's deadline into phone time: the time LEFT is measured on the server
         * (deadline - serverTime), then added to the phone's own clock. A phone whose clock is minutes off
         * therefore still reminds at the right moment.
         */
        fun localDeadline(serverDueAt: Instant, serverTime: Instant, phoneNow: Instant): Instant =
            phoneNow.plus(Duration.between(serverTime, serverDueAt))
    }
}
