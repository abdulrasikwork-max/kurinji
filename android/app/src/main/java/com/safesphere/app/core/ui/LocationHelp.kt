package com.safesphere.app.core.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.safesphere.app.core.location.LocationPermissionStatus
import com.safesphere.app.core.location.LocationPermissions

/**
 * Phase 16 location audit: one way for a screen to ask for location in context and to send the person to the right
 * settings page when asking again would do nothing (a permanent "Don't allow") or when location is switched off.
 * Register it in the fragment's constructor phase (it registers an activity-result launcher).
 */
class LocationAsk(fragment: Fragment, onResult: (LocationPermissionStatus) -> Unit) {

    /** Known only after the person answered once in this screen: Android cannot tell earlier. */
    var permanentlyDenied = false
        private set

    private val launcher: ActivityResultLauncher<Array<String>> =
        fragment.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val status = LocationPermissions.statusAfterRequest(
                fineGranted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true,
                coarseGranted = result[Manifest.permission.ACCESS_COARSE_LOCATION] == true,
                shouldShowRationale = fragment.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)
            )
            permanentlyDenied = status == LocationPermissionStatus.PERMANENTLY_DENIED
            onResult(status)
        }

    /** Shows Android's dialog (on Android 12+ it also lets an approximate permission become precise). */
    fun ask() = launcher.launch(LocationPermissions.REQUESTED)
}

object LocationSettings {

    /** This app's page in Android's settings (permissions live there). */
    fun openAppSettings(fragment: Fragment) = start(fragment,
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", fragment.requireContext().packageName, null)))

    /** The phone's location switch. */
    fun openLocationSettings(fragment: Fragment) = start(fragment, Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))

    private fun start(fragment: Fragment, intent: Intent) {
        try {
            fragment.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            // Some phones have no such page; the notice on screen still says what to do.
        }
    }
}
