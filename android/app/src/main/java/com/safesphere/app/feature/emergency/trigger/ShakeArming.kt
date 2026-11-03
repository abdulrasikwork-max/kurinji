package com.safesphere.app.feature.emergency.trigger

/**
 * Phase 17F (D-4): when the phone listens for the shake gesture at all. Pure and unit-tested.
 *
 * It listens only for a person who switched it on, signed in, while no SOS is running, and
 *  - while IRL is on the screen, or
 *  - while a Safe Walk runs (the existing foreground service keeps the app alive) AND notifications are allowed, because
 *    a shake with the screen off can only offer its cancellable countdown as a notification. Without that notification
 *    there would be no way to cancel, so then it does not listen in the background at all.
 *
 * Never otherwise: no always-on listening, no new service, no "boot" receiver.
 */
object ShakeArming {

    data class Inputs(
        val switchedOn: Boolean,
        val signedIn: Boolean,
        val sosRunning: Boolean,
        val appVisible: Boolean,
        val walkRunning: Boolean,
        val notificationsAllowed: Boolean
    )

    fun shouldListen(i: Inputs): Boolean =
        i.switchedOn && i.signedIn && !i.sosRunning && (i.appVisible || (i.walkRunning && i.notificationsAllowed))
}
