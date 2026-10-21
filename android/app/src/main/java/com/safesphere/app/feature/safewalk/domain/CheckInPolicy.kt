package com.safesphere.app.feature.safewalk.domain

import com.safesphere.app.core.escalation.EscalationLevel
import java.time.Duration
import java.time.Instant

/** Where the person is relative to the next check-in. */
enum class CheckInPhase {
    /** Plenty of time left. */
    ON_TRACK,

    /** The deadline is close: a gentle reminder (the "warning"). */
    DUE_SOON,

    /**
     * Level 1: the phone prompts "Are you OK?". Either the deadline passed, or (Phase 4C) a [Concern] was raised
     * earlier: then [CheckInStatus.concern] says why.
     */
    MISSED,

    /** No answer within the response window: escalation level 2. */
    NO_RESPONSE
}

data class CheckInStatus(
    val phase: CheckInPhase,
    val level: EscalationLevel,
    val dueAt: Instant,
    /** When the phase will change next if nothing happens; null once at the top of the automatic ladder. */
    val nextChangeAt: Instant?,
    /** Null: a check-in prompt. Otherwise the movement concern that opened the prompt early (Phase 4C). */
    val concern: ConcernKind? = null
)

/**
 * The phone's check-in and escalation policy. Pure Kotlin and stateless (a function of the deadline, an open
 * concern and the time), so it works offline, survives restarts (recomputed from the stored values), and is
 * unit-tested.
 *
 * A concern (route deviation, unexpected stop) simply opens the same prompt EARLIER than the deadline: level 1
 * at once, level 2 if nobody answers within the same response window. It never goes above level 2 on its own:
 * level 3 needs the person to confirm SOS (proportional ladder).
 */
class CheckInPolicy(
    private val warningLead: Duration = Duration.ofSeconds(60),
    private val responseWindow: Duration = Duration.ofMinutes(2)
) {

    fun evaluate(dueAt: Instant, now: Instant, concern: Concern? = null): CheckInStatus {
        if (concern != null && concern.raisedAt.isBefore(dueAt) && !concern.raisedAt.isAfter(now)) {
            val noResponseAt = concern.raisedAt.plus(responseWindow)
            return if (now.isBefore(noResponseAt)) {
                CheckInStatus(CheckInPhase.MISSED, EscalationLevel.CHECK_IN_MISSED, dueAt, noResponseAt, concern.kind)
            } else {
                CheckInStatus(CheckInPhase.NO_RESPONSE, EscalationLevel.NO_RESPONSE, dueAt, null, concern.kind)
            }
        }
        val warnAt = dueAt.minus(warningLead)
        val noResponseAt = dueAt.plus(responseWindow)
        return when {
            now.isBefore(warnAt) -> CheckInStatus(CheckInPhase.ON_TRACK, EscalationLevel.NORMAL, dueAt, warnAt)
            now.isBefore(dueAt) -> CheckInStatus(CheckInPhase.DUE_SOON, EscalationLevel.NORMAL, dueAt, dueAt)
            now.isBefore(noResponseAt) -> CheckInStatus(CheckInPhase.MISSED, EscalationLevel.CHECK_IN_MISSED, dueAt, noResponseAt)
            else -> CheckInStatus(CheckInPhase.NO_RESPONSE, EscalationLevel.NO_RESPONSE, dueAt, null)
        }
    }

    companion object {
        /**
         * Converts the server's deadline into phone time: the time LEFT is measured on the server
         * (deadline - serverTime), then added to the phone's own clock. A phone whose clock is minutes off
         * therefore still counts down correctly.
         */
        fun localDeadline(serverDueAt: Instant, serverTime: Instant, phoneNow: Instant): Instant =
            phoneNow.plus(Duration.between(serverTime, serverDueAt))
    }
}
