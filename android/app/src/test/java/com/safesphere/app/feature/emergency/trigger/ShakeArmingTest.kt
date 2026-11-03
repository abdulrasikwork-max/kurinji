package com.safesphere.app.feature.emergency.trigger

import org.junit.Assert.assertEquals
import org.junit.Test

/** Phase 17F (D-4): when the phone listens for the shake gesture, and when it must not. */
class ShakeArmingTest {

    private val base = ShakeArming.Inputs(
        switchedOn = true, signedIn = true, sosRunning = false, appVisible = true, walkRunning = false, notificationsAllowed = true
    )

    private fun listens(change: (ShakeArming.Inputs) -> ShakeArming.Inputs = { it }) = ShakeArming.shouldListen(change(base))

    @Test
    fun `listens while IRL is on the screen for a person who switched it on`() = assertEquals(true, listens())

    @Test
    fun `never listens when the person did not switch it on`() = assertEquals(false, listens { it.copy(switchedOn = false) })

    @Test
    fun `never listens when signed out`() = assertEquals(false, listens { it.copy(signedIn = false) })

    @Test
    fun `stops listening once an SOS is running`() = assertEquals(false, listens { it.copy(sosRunning = true) })

    @Test
    fun `off the screen it listens only during a Safe Walk`() {
        assertEquals(false, listens { it.copy(appVisible = false) })
        assertEquals(true, listens { it.copy(appVisible = false, walkRunning = true) })
    }

    @Test
    fun `off the screen it needs notifications, because the countdown must be cancellable`() {
        assertEquals(false, listens { it.copy(appVisible = false, walkRunning = true, notificationsAllowed = false) })
        // On the screen the countdown has its own Cancel button, so notifications do not matter.
        assertEquals(true, listens { it.copy(notificationsAllowed = false) })
    }
}
