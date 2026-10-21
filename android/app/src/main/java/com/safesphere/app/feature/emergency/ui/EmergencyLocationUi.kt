package com.safesphere.app.feature.emergency.ui

import androidx.annotation.StringRes
import com.safesphere.app.R
import com.safesphere.app.core.location.AccuracyLevel
import com.safesphere.app.core.location.accuracyLevelOf
import com.safesphere.app.feature.emergency.location.LocationSharingState
import com.safesphere.app.feature.emergency.location.LocationSharingStatus

/**
 * Everything the "Your location" part of the active emergency screen shows.
 *
 * @param permissionBlocked location was permanently denied: only the app's Settings page can change it
 * @param backgroundTrackingActive the tracking foreground service is running, so updates continue with the
 *                                 screen off; when false the person is told to keep SafeSphere open
 */
data class EmergencyLocationUi(
    val sharing: LocationSharingState = LocationSharingState(),
    val permissionBlocked: Boolean = false,
    val backgroundTrackingActive: Boolean = false
)

enum class LocationCardAction { ALLOW_LOCATION, OPEN_APP_SETTINGS, OPEN_LOCATION_SETTINGS }

/** What the location card says and offers. Pure mapping, unit-tested. */
data class LocationCard(
    @StringRes val message: Int,
    val isWarning: Boolean,
    val action: LocationCardAction? = null,
    /** The server's own explanation (CONFLICT), shown instead of [message] when present. */
    val serverMessage: String? = null,
    val showDetails: Boolean = false,
    val showKeepAppOpenHint: Boolean = false,
    val showApproximateHint: Boolean = false,
    val showLowAccuracyHint: Boolean = false,
    val showRejectedHint: Boolean = false
)

fun locationCardFor(ui: EmergencyLocationUi): LocationCard {
    val sharing = ui.sharing
    val hasFix = sharing.lastFix != null
    val base = when {
        sharing.signedOut -> LocationCard(R.string.location_card_signed_out, isWarning = true)
        sharing.status == LocationSharingStatus.PERMISSION_NEEDED && ui.permissionBlocked ->
            LocationCard(R.string.location_card_permission_blocked, isWarning = true, action = LocationCardAction.OPEN_APP_SETTINGS)
        sharing.status == LocationSharingStatus.PERMISSION_NEEDED ->
            LocationCard(R.string.location_card_permission_needed, isWarning = true, action = LocationCardAction.ALLOW_LOCATION)
        sharing.status == LocationSharingStatus.SERVICES_UNAVAILABLE ->
            LocationCard(R.string.location_card_services_unavailable, isWarning = true)
        sharing.status == LocationSharingStatus.LOCATION_DISABLED ->
            LocationCard(R.string.location_card_location_off, isWarning = true, action = LocationCardAction.OPEN_LOCATION_SETTINGS)
        sharing.uploadsStopped ->
            LocationCard(R.string.location_card_uploads_stopped, isWarning = true, serverMessage = sharing.uploadsStoppedMessage)
        sharing.pendingCount > 0 && !sharing.isOnline ->
            LocationCard(R.string.location_card_offline, isWarning = true)
        sharing.status == LocationSharingStatus.UNAVAILABLE ->
            LocationCard(R.string.location_card_unavailable, isWarning = true)
        sharing.status == LocationSharingStatus.AVAILABLE && sharing.pendingCount > 0 ->
            LocationCard(R.string.location_card_saving, isWarning = false)
        sharing.status == LocationSharingStatus.AVAILABLE ->
            LocationCard(R.string.location_card_saved, isWarning = false)
        else -> LocationCard(R.string.location_card_searching, isWarning = false)
    }
    val tracking = sharing.isTracking
    return base.copy(
        showDetails = hasFix && !sharing.signedOut,
        showKeepAppOpenHint = tracking && !ui.backgroundTrackingActive,
        showApproximateHint = tracking && sharing.isApproximate,
        showLowAccuracyHint = hasFix && accuracyLevelOf(sharing.lastFix?.accuracyMeters) == AccuracyLevel.POOR,
        showRejectedHint = sharing.rejectedCount > 0
    )
}
