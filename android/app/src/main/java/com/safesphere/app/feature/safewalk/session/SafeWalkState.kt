package com.safesphere.app.feature.safewalk.session

import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.safewalk.domain.CheckInPhase
import com.safesphere.app.feature.safewalk.domain.CheckInStatus
import com.safesphere.app.feature.safewalk.domain.Journey
import com.safesphere.app.feature.safewalk.domain.JourneyStatus
import java.time.Instant
import kotlinx.coroutines.flow.StateFlow

/**
 * "On my way" as the screen, the banners and the notification actions see it (N6, D22; code name Safe Walk).
 * A reminder, not tracking: there is no location here at all.
 *
 * @param localDueAt      the reminder time in PHONE time
 * @param endedStatus     set when this walk ended on this phone (the screen shows it once, then acknowledges)
 * @param pendingSync     changes not yet confirmed by the server (More time, the end)
 * @param restoredOffline restored from the phone's own record because the server could not be reached
 */
data class SafeWalkState(
    val journey: Journey? = null,
    val localDueAt: Instant? = null,
    val checkIn: CheckInStatus? = null,
    val endedStatus: JourneyStatus? = null,
    val pendingSync: Int = 0,
    val isOnline: Boolean = true,
    val restoredOffline: Boolean = false,
    val signedOut: Boolean = false
) {
    val isActive: Boolean get() = journey != null && endedStatus == null && !signedOut

    val phase: CheckInPhase? get() = if (isActive) checkIn?.phase else null

    override fun toString(): String = "SafeWalkState(journey=${journey?.id}, phase=$phase, ended=$endedStatus)"
}

/** What screens need from the walk holder. Production: [SafeWalkSession]. */
interface SafeWalkController {
    val state: StateFlow<SafeWalkState>

    /** Starts a walk on the server (the server computes the reminder time). [label] = the person's own words or null. */
    suspend fun start(label: String?, minutes: Int): ApiResult<Journey>

    /** After a restart: resumes an ACTIVE walk from the server, or from the phone's record when offline. */
    suspend fun recover()

    /** "More time": a new reminder one interval from now. Works offline; the server is told when possible. */
    fun checkIn()

    /** "I'm there". Ends the walk on the phone at once; the server is told when possible. */
    fun complete()

    fun cancel()

    /** An SOS was started: the server already ended the walk as EMERGENCY; stop the reminders here. */
    fun onEmergencyStarted()

    /** The screen has shown the "walk ended" state. */
    fun acknowledgeEnded()
}

/** The reminder notifications. Production: [AndroidSafeWalkAlerts]. Tests: a fake. */
interface SafeWalkAlerts {
    /** "There yet?" (MISSED) or the one nudge with SOS / Call 112 (NO_RESPONSE). Nothing for the other phases. */
    fun show(phase: CheckInPhase)
    fun clear()
}

/**
 * Wakes the phone at the reminder times even when IRL is not running (N6, D22): an alarm that needs no permission
 * (`AlarmManager.setAndAllowWhileIdle`), so it may come a few minutes late (the screen says so). Production:
 * [AndroidReminderScheduler]. Tests: a fake.
 */
interface ReminderScheduler {
    /** Replaces any earlier alarms of this walk with these: the reminder and the one nudge. */
    fun schedule(journeyId: Long, reminderAt: Instant, nudgeAt: Instant)
    fun cancel()
}
