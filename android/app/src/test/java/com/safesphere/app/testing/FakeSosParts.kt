package com.safesphere.app.testing

import com.safesphere.app.feature.auth.domain.AuthSession
import com.safesphere.app.feature.auth.domain.AuthUser
import com.safesphere.app.feature.emergency.domain.ActiveEmergencyStore
import com.safesphere.app.feature.emergency.domain.EmergencyRepository
import com.safesphere.app.feature.emergency.session.EmergencyRebinder
import com.safesphere.app.feature.emergency.session.SosRecorder
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent

/** A signed-in session for [userId] (the recorder only records for the signed-in person). */
fun sessionFor(userId: Long) = AuthSession("token", AuthUser(userId, "Asha", "asha@example.com"))

/**
 * A REAL [SosRecorder] in the test's background scope (Phase 11). Background work does not run in
 * advanceUntilIdle(): use [settle] (or runCurrent / advanceTimeBy) after each step.
 */
fun TestScope.sosRecorderForTest(
    repository: EmergencyRepository,
    store: ActiveEmergencyStore = FakeActiveEmergencyStore(),
    userId: Long = 7L,
    online: MutableStateFlow<Boolean> = MutableStateFlow(true),
    session: MutableStateFlow<AuthSession?> = MutableStateFlow(sessionFor(userId)),
    rebinders: List<EmergencyRebinder> = emptyList(),
    dialPrompts: MutableList<Long> = mutableListOf(),
    now: () -> Instant = { Instant.ofEpochMilli(testScheduler.currentTime).plusSeconds(1_800_000_000) },
    references: Iterator<String> = generateSequence(1) { it + 1 }
        .map { "00000000-0000-4000-8000-%012d".format(it) }.iterator()
): SosRecorder = SosRecorder(
    scope = backgroundScope,
    store = store,
    repository = repository,
    isOnline = online,
    session = session,
    rebinders = { rebinders },
    onDialPromptRecorded = { dialPrompts += it },
    now = now,
    newReference = { references.next() }
)

/** Lets foreground AND background work run to rest at the current virtual time. */
fun TestScope.settle() {
    repeat(4) {
        advanceUntilIdle()
        runCurrent()
    }
}
