package com.safesphere.app.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real-device sprint, Track H (Android 13 emulator): on the first SOS of a phone that had not yet allowed notifications,
 * the system dialog "Allow IRL to send you notifications?" appeared over the SOS screen and the tracking service did NOT run
 * while it stayed open (still not running 22 s later), so leaving the app would have stopped the location. Android lets a
 * foreground service run without that permission, so the service now starts FIRST and the question is asked next to it.
 */
class Phase17HTrackingStarterTest {

    private val source = File("src/main/java/com/safesphere/app/core/tracking/TrackingServiceStarter.kt").readText(Charsets.UTF_8)
    private val body = source.substringAfter("fun startIfNeeded(").substringBefore("fun reset()")

    @Test
    fun `the service is started before the notification question is asked`() {
        val start = body.indexOf("start(key)")
        val ask = body.indexOf("notificationLauncher.launch")
        assertTrue("start(key) is called in startIfNeeded", start >= 0)
        assertTrue("the notification question is still asked", ask >= 0)
        assertTrue("the service starts first", start < ask)
    }

    @Test
    fun `allowing late starts the service again so its notification appears`() {
        val callback = source.substringAfter("RequestPermission()) { granted ->").substringBefore("\n        }")
        assertTrue("only when allowed", callback.contains("granted"))
        assertTrue("only when the question was asked", callback.contains("wasOpen"))
        assertFalse("not tied to the screen session that onStop clears", callback.contains("requestedFor"))
        assertTrue("it starts the service again (startForeground posts the notification)", callback.contains("SafetyTrackingService.start("))
    }

    @Test
    fun `leaving the screen with the question open does not forget the question`() {
        // Galaxy A23 (Android 14): Home with the dialog open, back, Allow -> no notification, because reset() had cleared the guard.
        val reset = source.substringAfter("fun reset()").substringBefore("}")
        assertFalse("reset() must not clear the open question", reset.contains("questionOpen"))
        val launch = body.substringAfter("questionOpen = true")
        assertTrue("the flag is set right before the question is launched", launch.trimStart().startsWith("notificationLauncher.launch"))
    }

    @Test
    fun `nothing makes the service wait for the answer`() {
        assertFalse("no pending-answer state", source.contains("waitingFor"))
        assertTrue("the first start happens before the question", body.indexOf("start(key)") < body.indexOf("notificationLauncher.launch"))
        // exactly one launch, and only for a missing runtime permission the screen asked to prompt for
        assertEquals(1, Regex("notificationLauncher\\.launch").findAll(source).count())
        assertTrue(body.contains("consumeNotificationPrompt()"))
    }

    @Test
    fun `the question is asked at most once per screen session and not at all for the walk`() {
        val walk = File("src/main/java/com/safesphere/app/feature/safewalk/ui/SafeWalkFragment.kt").readText(Charsets.UTF_8)
        assertTrue("the walk never asks from the starter", walk.contains("TrackingServiceStarter(this) { false }"))
        val sos = File("src/main/java/com/safesphere/app/feature/emergency/ui/EmergencyFragment.kt").readText(Charsets.UTF_8)
        assertTrue("the SOS asks through its one-shot view model flag", sos.contains("TrackingServiceStarter(this) { viewModel.consumeNotificationPrompt() }"))
    }
}
