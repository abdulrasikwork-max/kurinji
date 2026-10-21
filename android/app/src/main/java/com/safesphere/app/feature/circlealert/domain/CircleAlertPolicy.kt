package com.safesphere.app.feature.circlealert.domain

import com.safesphere.app.feature.safewalk.domain.ConcernKind
import java.time.Duration
import java.time.Instant

/** What the automatic alerts remember about one Safe Walk. */
data class WalkAlertMemory(
    /** How many "no response" alerts this walk has caused (capped, to avoid flooding the circle). */
    val alertsSent: Int = 0,
    /** An alert was sent for the current no-response episode and the circle has not been told "all clear" yet. */
    val awaitingAllClear: Boolean = false,
    val updatedAt: Instant
)

/** What the automatic alerts remember about one emergency. */
data class EmergencyAlertMemory(
    val startedAt: Instant,
    val startSent: Boolean = false,
    /** Too old when first seen (e.g. restored hours later): never alerted automatically. */
    val skipped: Boolean = false,
    /** The first SOS message had no location: send one location follow-up when a fix arrives (soon enough). */
    val locationFollowUpPending: Boolean = false,
    val endSent: Boolean = false,
    val updatedAt: Instant
)

/**
 * Kept on the phone (no location, no numbers) so an app restart never sends the same alert twice.
 * Entries older than [CircleAlertPolicy.MEMORY_TTL] are dropped.
 */
data class AlertMemory(
    val walks: Map<Long, WalkAlertMemory> = emptyMap(),
    val emergencies: Map<Long, EmergencyAlertMemory> = emptyMap()
)

/** Something happened that may need the Safety Circle to be told. */
sealed interface AlertTrigger {
    /** A Safe Walk reached level 2 (no answer to "Are you OK?"). */
    data class WalkNoResponse(val journeyId: Long, val concern: ConcernKind?) : AlertTrigger

    /** The person answered after level 2 (checked in, arrived, or cancelled the walk). */
    data class WalkAnswered(val journeyId: Long) : AlertTrigger

    /** The walk turned into an SOS: the emergency messages take over; no "all clear". */
    data class WalkBecameEmergency(val journeyId: Long) : AlertTrigger

    /**
     * An emergency is ACTIVE (re-evaluated whenever a fix arrives or the location wait is over). [locationExpected] is
     * false when no fix can come (no permission, location off, no Play services): then there is nothing to wait for.
     */
    data class EmergencyActive(
        val emergencyId: Long,
        val startedAt: Instant,
        val hasLocation: Boolean,
        val locationExpected: Boolean = true
    ) : AlertTrigger

    data class EmergencyEnded(val emergencyId: Long, val resolved: Boolean) : AlertTrigger
}

data class AlertDecision(
    val memory: AlertMemory,
    /** Messages to send now (to every enabled contact). Empty = nothing to send. */
    val send: List<CircleMessageKind>,
    /** If not null, the trigger should be evaluated again at this time (waiting for a first location). */
    val reevaluateAt: Instant? = null
)

/**
 * When the Safety Circle is told what (Phase 4D, escalation communication). Pure Kotlin, unit-tested; only used when
 * the person switched automatic SMS alerts on. Proportional and calm:
 *
 * - Level 2 on a Safe Walk -> one "did not answer" message per no-response episode, at most [MAX_WALK_ALERTS] per walk.
 *   When the person answers afterwards -> one "all clear", so nobody worries longer than needed.
 * - Level 3 (SOS) -> one "SOS started" message. It waits up to [LOCATION_WAIT] for a first location so the message is
 *   useful (not at all when no fix can come); without one it is sent anyway, and one location follow-up is sent if a
 *   fix arrives within [LOCATION_FOLLOW_UP_WINDOW]. An SOS cancelled before the first message went out tells nobody.
 *   When the SOS ends after it was sent -> one "resolved" or "false alarm" message.
 * - An emergency first seen more than [STALE_EMERGENCY] after it started (e.g. restored later) is never alerted
 *   automatically: a late SMS would mislead. The screen still offers sending manually.
 * - Nothing is ever sent twice for the same event, also after a restart ([AlertMemory] is stored).
 */
class CircleAlertPolicy(
    private val maxWalkAlerts: Int = MAX_WALK_ALERTS,
    private val locationWait: Duration = LOCATION_WAIT,
    private val locationFollowUpWindow: Duration = LOCATION_FOLLOW_UP_WINDOW,
    private val staleEmergency: Duration = STALE_EMERGENCY
) {

    fun decide(memory: AlertMemory, trigger: AlertTrigger, now: Instant): AlertDecision {
        val pruned = prune(memory, now)
        return when (trigger) {
            is AlertTrigger.WalkNoResponse -> {
                val walk = pruned.walks[trigger.journeyId] ?: WalkAlertMemory(updatedAt = now)
                if (walk.awaitingAllClear || walk.alertsSent >= maxWalkAlerts) {
                    AlertDecision(pruned, emptyList())
                } else {
                    val next = walk.copy(alertsSent = walk.alertsSent + 1, awaitingAllClear = true, updatedAt = now)
                    AlertDecision(pruned.withWalk(trigger.journeyId, next), listOf(CircleMessageKind.WALK_NO_RESPONSE))
                }
            }
            is AlertTrigger.WalkAnswered -> {
                val walk = pruned.walks[trigger.journeyId]
                if (walk == null || !walk.awaitingAllClear) {
                    AlertDecision(pruned, emptyList())
                } else {
                    AlertDecision(
                        pruned.withWalk(trigger.journeyId, walk.copy(awaitingAllClear = false, updatedAt = now)),
                        listOf(CircleMessageKind.ALL_CLEAR)
                    )
                }
            }
            is AlertTrigger.WalkBecameEmergency -> {
                val walk = pruned.walks[trigger.journeyId] ?: return AlertDecision(pruned, emptyList())
                AlertDecision(pruned.withWalk(trigger.journeyId, walk.copy(awaitingAllClear = false, updatedAt = now)), emptyList())
            }
            is AlertTrigger.EmergencyActive -> emergencyActive(pruned, trigger, now)
            is AlertTrigger.EmergencyEnded -> {
                val emergency = pruned.emergencies[trigger.emergencyId]
                if (emergency == null || emergency.endSent) {
                    AlertDecision(pruned, emptyList())
                } else if (!emergency.startSent) {
                    // Ended before the first message went out: nobody was told, nobody will be.
                    AlertDecision(pruned.withEmergency(trigger.emergencyId, emergency.copy(endSent = true, updatedAt = now)), emptyList())
                } else {
                    val kind = if (trigger.resolved) CircleMessageKind.SOS_RESOLVED else CircleMessageKind.SOS_CANCELLED
                    AlertDecision(
                        pruned.withEmergency(trigger.emergencyId,
                            emergency.copy(endSent = true, locationFollowUpPending = false, updatedAt = now)),
                        listOf(kind)
                    )
                }
            }
        }
    }

    private fun emergencyActive(memory: AlertMemory, trigger: AlertTrigger.EmergencyActive, now: Instant): AlertDecision {
        val known = memory.emergencies[trigger.emergencyId]
        if (known == null && Duration.between(trigger.startedAt, now) > staleEmergency) {
            val skipped = EmergencyAlertMemory(trigger.startedAt, skipped = true, updatedAt = now)
            return AlertDecision(memory.withEmergency(trigger.emergencyId, skipped), emptyList())
        }
        val emergency = known ?: EmergencyAlertMemory(trigger.startedAt, updatedAt = now)
        if (emergency.skipped || emergency.endSent) return AlertDecision(memory.withEmergency(trigger.emergencyId, emergency), emptyList())

        if (emergency.startSent) {
            val followUpOpen = Duration.between(emergency.startedAt, now) <= locationFollowUpWindow
            return if (emergency.locationFollowUpPending && trigger.hasLocation && followUpOpen) {
                AlertDecision(
                    memory.withEmergency(trigger.emergencyId, emergency.copy(locationFollowUpPending = false, updatedAt = now)),
                    listOf(CircleMessageKind.SOS_LOCATION)
                )
            } else {
                AlertDecision(memory.withEmergency(trigger.emergencyId, emergency), emptyList())
            }
        }

        val waitUntil = emergency.startedAt.plus(locationWait)
        return if (trigger.hasLocation || !trigger.locationExpected || !now.isBefore(waitUntil)) {
            val sent = emergency.copy(startSent = true, locationFollowUpPending = !trigger.hasLocation, updatedAt = now)
            AlertDecision(memory.withEmergency(trigger.emergencyId, sent), listOf(CircleMessageKind.SOS_STARTED))
        } else {
            AlertDecision(memory.withEmergency(trigger.emergencyId, emergency), emptyList(), reevaluateAt = waitUntil)
        }
    }

    private fun prune(memory: AlertMemory, now: Instant): AlertMemory {
        val cutoff = now.minus(MEMORY_TTL)
        return AlertMemory(
            walks = memory.walks.filterValues { it.updatedAt.isAfter(cutoff) },
            emergencies = memory.emergencies.filterValues { it.updatedAt.isAfter(cutoff) }
        )
    }

    private fun AlertMemory.withWalk(id: Long, walk: WalkAlertMemory) = copy(walks = walks + (id to walk))

    private fun AlertMemory.withEmergency(id: Long, emergency: EmergencyAlertMemory) =
        copy(emergencies = emergencies + (id to emergency))

    companion object {
        const val MAX_WALK_ALERTS = 3
        val LOCATION_WAIT: Duration = Duration.ofSeconds(30)
        val LOCATION_FOLLOW_UP_WINDOW: Duration = Duration.ofMinutes(10)
        val STALE_EMERGENCY: Duration = Duration.ofMinutes(30)
        val MEMORY_TTL: Duration = Duration.ofDays(2)
    }
}
