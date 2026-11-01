package com.safesphere.app.core.location

/**
 * Phase 17D: the ONE thing a screen should offer so location can work, from facts it can read (pure, unit-tested).
 * Every screen that needs location used to decide this on its own, and some never offered the way out of a permanent
 * "Don't allow" or of "approximate only".
 */
enum class LocationNeed {
    /** Nothing to do. */
    NONE,

    /** Show Android's permission dialog. */
    ALLOW,

    /** Permission was refused for good: only the app's settings page can change it. */
    OPEN_APP_SETTINGS,

    /** The phone's location switch is off. */
    TURN_ON,

    /** Approximate only, and precise is needed: show Android's dialog (on Android 12+ it offers the upgrade). */
    UPGRADE_PRECISE,

    /** Precise was already declined in this screen's dialog: Android will not ask again; the settings toggle remains. */
    UPGRADE_PRECISE_IN_SETTINGS
}

object LocationNeeds {

    /**
     * [wantsPrecise]: this use needs precise location (reporting, adding a place, route checks), not only the area.
     * [permanentlyDenied] / [preciseDeclined] come from [com.safesphere.app.core.ui.LocationAsk]: what the dialog
     * answered, because Android cannot say it beforehand. The upgrade is judged by the OBSERVED result (the dialog
     * came back still approximate), never by rationale semantics.
     */
    fun of(
        access: LocationAccess,
        locationEnabled: Boolean,
        wantsPrecise: Boolean,
        permanentlyDenied: Boolean = false,
        preciseDeclined: Boolean = false
    ): LocationNeed = when {
        access == LocationAccess.NONE ->
            if (permanentlyDenied) LocationNeed.OPEN_APP_SETTINGS else LocationNeed.ALLOW
        !locationEnabled -> LocationNeed.TURN_ON
        access == LocationAccess.APPROXIMATE && wantsPrecise ->
            if (preciseDeclined) LocationNeed.UPGRADE_PRECISE_IN_SETTINGS else LocationNeed.UPGRADE_PRECISE
        else -> LocationNeed.NONE
    }
}
