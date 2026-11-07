package com.safesphere.app.core.tracking

import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import com.safesphere.app.core.notifications.NotificationPermission

/**
 * Starts [SafetyTrackingService] from a visible screen (SOS or Safe Walk), and asks for the Android 13+ notification
 * permission once if it is still missing. Create it as a Fragment property (it registers an activity-result
 * launcher, which must happen before the fragment is started) and call [reset] from onStop, so the
 * service is requested again the next time the screen is visible (it may have been killed meanwhile).
 *
 * **The service never waits for the permission dialog** (real-device sprint, Track H, Android 13 emulator): the first SOS
 * on a phone that had not yet allowed notifications showed the system dialog over the SOS screen, and while it stayed open
 * the service was not running, so leaving the app would have stopped the location. Android lets a foreground service run
 * without that permission (it only hides the notification), so the service starts first and the question is asked next to it.
 */
class TrackingServiceStarter(
    private val fragment: Fragment,
    /** True exactly once per screen session (kept in the ViewModel, so rotation does not ask twice). */
    private val consumeNotificationPrompt: () -> Boolean
) {
    private var requestedFor: Long? = null

    // True from the moment the question is asked until it is answered. It is NOT cleared by reset(): the person may press Home
    // with the dialog open and answer after coming back (Galaxy A23, Android 14: the guard `requestedFor != null` had been cleared
    // by onStop, so a late "Allow" never started the service again and its notification never appeared).
    private var questionOpen = false

    // The service already runs. If the person allows notifications, start it once more: onStartCommand enters the foreground
    // again, which is what posts the notification now that the permission exists (verified on Android 13: without this a
    // service started before the answer never showed its notification, not even 50 s after "Allow"). If nothing is being
    // tracked any more the service stops itself at once (onStartCommand), so a late answer cannot leave a stray service.
    private val notificationLauncher =
        fragment.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val wasOpen = questionOpen
            questionOpen = false
            if (granted && wasOpen && fragment.isAdded) SafetyTrackingService.start(fragment.requireContext())
        }

    /**
     * @param key the emergency or journey the service is for (one request per key per visible period)
     * @param shouldRun location is being tracked (so permission is granted)
     * @param alreadyRunning the service reported it is in the foreground
     */
    fun startIfNeeded(key: Long?, shouldRun: Boolean, alreadyRunning: Boolean) {
        if (key == null || !shouldRun || alreadyRunning || requestedFor == key) return
        if (!isVisible()) return
        val context = fragment.requireContext()
        start(key)
        if (NotificationPermission.isRuntimePermission() && !NotificationPermission.isGranted(context) &&
            consumeNotificationPrompt()
        ) {
            questionOpen = true
            notificationLauncher.launch(NotificationPermission.PERMISSION)
        }
    }

    fun reset() {
        requestedFor = null
    }

    private fun start(key: Long) {
        if (!isVisible()) return
        requestedFor = key
        // If Android refuses, the screen keeps showing its "keep SafeSphere open" hint.
        SafetyTrackingService.start(fragment.requireContext())
    }

    /** The fragment's own lifecycle: a permission callback can arrive when there is no view. */
    private fun isVisible(): Boolean =
        fragment.isAdded && fragment.view != null && fragment.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
}
