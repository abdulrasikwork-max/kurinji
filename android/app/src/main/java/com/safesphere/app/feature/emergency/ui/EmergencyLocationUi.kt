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
    val backgroundTrackingActive: Boolean = false,
    /**
     * Phase 17F: the phone's clock (epoch millis), refreshed every few seconds by the screen's view model, so a position
     * that stops being new can be labelled with its age. Null = unknown (no label is invented).
     */
    val nowMillis: Long? = null
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
    val showRejectedHint: Boolean = false,
    /** Phase 17F: set when the shown position is no longer new; the screen then says so and how old it is. */
    val lastKnown: LastKnownFix? = null
)

/**
 * Phase 17F: the position on screen is the LAST KNOWN one, [ageSeconds] old (phone clock). It is never presented as
 * where the person is now. Shown (and sent to the Safety Circle, which always said "Last known location") with its time.
 */
data class LastKnownFix(val recordedAt: java.time.Instant, val ageSeconds: Long)

/** A position older than this is called last known (the same minute the SOS screen waits before saying "no new position"). */
const val LAST_KNOWN_AFTER_SECONDS = 60L

fun lastKnownOf(fix: com.safesphere.app.core.location.LocationFix?, nowMillis: Long?): LastKnownFix? {
    if (fix == null || nowMillis == null) return null
    val age = ((nowMillis - fix.recordedAt.toEpochMilli()) / 1000).coerceAtLeast(0)
    return if (age >= LAST_KNOWN_AFTER_SECONDS) LastKnownFix(fix.recordedAt, age) else null
}

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
        showRejectedHint = sharing.rejectedCount > 0,
        lastKnown = if (hasFix && !sharing.signedOut) lastKnownOf(sharing.lastFix, ui.nowMillis) else null
    )
}
