package com.safesphere.app.feature.emergency

import com.safesphere.app.R
import com.safesphere.app.core.location.LocationAccess
import com.safesphere.app.feature.emergency.location.LocationSharingState
import com.safesphere.app.feature.emergency.location.LocationSharingStatus
import com.safesphere.app.feature.emergency.ui.EmergencyLocationUi
import com.safesphere.app.feature.emergency.ui.LocationCardAction
import com.safesphere.app.feature.emergency.ui.locationCardFor
import com.safesphere.app.testing.FakeLocationProvider.Companion.fix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the "Your location" card tells the person in every situation. */
class LocationCardTest {

    private fun ui(
        status: LocationSharingStatus,
        blocked: Boolean = false,
        service: Boolean = true,
        transform: (LocationSharingState) -> LocationSharingState = { it }
    ) = EmergencyLocationUi(
        sharing = transform(LocationSharingState(emergencyId = 1, status = status, access = LocationAccess.PRECISE)),
        permissionBlocked = blocked,
        backgroundTrackingActive = service
    )

    @Test
    fun `permission denied offers Allow, permanently denied offers Settings`() {
        val denied = locationCardFor(ui(LocationSharingStatus.PERMISSION_NEEDED) { it.copy(access = LocationAccess.NONE) })
        assertEquals(R.string.location_card_permission_needed, denied.message)
        assertEquals(LocationCardAction.ALLOW_LOCATION, denied.action)
        assertTrue(denied.isWarning)

        val blocked = locationCardFor(ui(LocationSharingStatus.PERMISSION_NEEDED, blocked = true))
        assertEquals(R.string.location_card_permission_blocked, blocked.message)
        assertEquals(LocationCardAction.OPEN_APP_SETTINGS, blocked.action)
    }

    @Test
    fun `location switched off offers the location settings`() {
        val card = locationCardFor(ui(LocationSharingStatus.LOCATION_DISABLED))
        assertEquals(R.string.location_card_location_off, card.message)
        assertEquals(LocationCardAction.OPEN_LOCATION_SETTINGS, card.action)
    }

    @Test
    fun `saved location is calm and honest, with details`() {
        val card = locationCardFor(ui(LocationSharingStatus.AVAILABLE) { it.copy(lastFix = fix()) })
        assertEquals(R.string.location_card_saved, card.message)
        assertFalse(card.isWarning)
        assertTrue(card.showDetails)
        assertNull(card.action)
        assertFalse(card.showKeepAppOpenHint)
    }

    @Test
    fun `offline with waiting points says they will be saved later`() {
        val card = locationCardFor(ui(LocationSharingStatus.AVAILABLE) { it.copy(pendingCount = 3, isOnline = false, lastFix = fix()) })
        assertEquals(R.string.location_card_offline, card.message)
        assertTrue(card.isWarning)
    }

    @Test
    fun `server refusal shows the server's own message`() {
        val card = locationCardFor(ui(LocationSharingStatus.AVAILABLE) {
            it.copy(uploadsStopped = true, uploadsStoppedMessage = "Location limit reached for this emergency")
        })
        assertEquals("Location limit reached for this emergency", card.serverMessage)
    }

    @Test
    fun `without the foreground service the person is told to keep the app open`() {
        assertTrue(locationCardFor(ui(LocationSharingStatus.SEARCHING, service = false)).showKeepAppOpenHint)
        // Not tracking (no permission): no service is expected, so no such hint.
        assertFalse(locationCardFor(ui(LocationSharingStatus.PERMISSION_NEEDED, service = false)).showKeepAppOpenHint)
    }

    @Test
    fun `approximate permission, low accuracy and rejected points are explained`() {
        val card = locationCardFor(ui(LocationSharingStatus.AVAILABLE) {
            it.copy(access = LocationAccess.APPROXIMATE, lastFix = fix(accuracy = 1_500f), rejectedCount = 2)
        })
        assertTrue(card.showApproximateHint)
        assertTrue(card.showLowAccuracyHint)
        assertTrue(card.showRejectedHint)
    }

    @Test
    fun `signed out and unavailable states are warnings`() {
        assertEquals(R.string.location_card_signed_out,
            locationCardFor(ui(LocationSharingStatus.AVAILABLE) { it.copy(signedOut = true) }).message)
        assertEquals(R.string.location_card_unavailable, locationCardFor(ui(LocationSharingStatus.UNAVAILABLE)).message)
        assertEquals(R.string.location_card_services_unavailable,
            locationCardFor(ui(LocationSharingStatus.SERVICES_UNAVAILABLE)).message)
    }
}
