package com.safesphere.app.core.tracking

import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import com.safesphere.app.core.notifications.NotificationPermission

/**
 * Starts [SafetyTrackingService] from a visible screen (SOS or Safe Walk), asking for the Android 13+
 * notification permission first, once. Create it as a Fragment property (it registers an activity-result
 * launcher, which must happen before the fragment is started) and call [reset] from onStop, so the
 * service is requested again the next time the screen is visible (it may have been killed meanwhile).
 */
class TrackingServiceStarter(
    private val fragment: Fragment,
    /** True exactly once per screen session (kept in the ViewModel, so rotation does not ask twice). */
    private val consumeNotificationPrompt: () -> Boolean
) {
    private var requestedFor: Long? = null
    private var waitingFor: Long? = null

    private val notificationLauncher =
        fragment.registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Granted or not, start now: without the permission Android only hides the notification.
            waitingFor?.let { start(it) }
            waitingFor = null
        }

    /**
     * @param key the emergency or journey the service is for (one request per key per visible period)
     * @param shouldRun location is being tracked (so permission is granted)
     * @param alreadyRunning the service reported it is in the foreground
     */
    fun startIfNeeded(key: Long?, shouldRun: Boolean, alreadyRunning: Boolean) {
        if (key == null || !shouldRun || alreadyRunning || requestedFor == key || waitingFor == key) return
        if (!isVisible()) return
        val context = fragment.requireContext()
        if (NotificationPermission.isRuntimePermission() && !NotificationPermission.isGranted(context) &&
            consumeNotificationPrompt()
        ) {
            waitingFor = key
            notificationLauncher.launch(NotificationPermission.PERMISSION)
            return
        }
        start(key)
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
