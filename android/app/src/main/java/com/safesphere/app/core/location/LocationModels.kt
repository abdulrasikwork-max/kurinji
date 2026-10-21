package com.safesphere.app.core.location

import java.time.Instant

/** Where a fix came from. Same names as the backend (emergency_locations.source). */
enum class LocationSource { FUSED, LAST_KNOWN, GPS, NETWORK, UNKNOWN }

/** What the person has allowed. Android 12+ lets them grant only an approximate location. */
enum class LocationAccess { NONE, APPROXIMATE, PRECISE }

/** One position reported by the phone. [accuracyMeters] is null when the phone did not say. */
data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
    val source: LocationSource,
    val recordedAt: Instant
) {
    /** Coordinates are never written to logs or crash reports. */
    override fun toString(): String = "LocationFix(location=<redacted>, source=$source, recordedAt=$recordedAt)"
}

/** Why no fix can be delivered right now. */
enum class LocationUnavailableReason {
    /** No location permission (never granted, denied, or revoked). */
    PERMISSION_DENIED,

    /** The phone's location setting is switched off. */
    LOCATION_DISABLED,

    /** Location is on but no fix is available at the moment (indoors, no signal...). Usually temporary. */
    NO_FIX,

    /** Google Play services are missing or out of date, so the fused provider cannot run. */
    SERVICES_UNAVAILABLE
}

/** What a [LocationProvider] stream emits. */
sealed interface LocationUpdate {
    data class Fix(val fix: LocationFix) : LocationUpdate
    data class Unavailable(val reason: LocationUnavailableReason) : LocationUpdate
}

/** How useful a fix is for someone trying to find the person. */
enum class AccuracyLevel { GOOD, FAIR, POOR, UNKNOWN }

/** Pure classification so the UI can warn about a rough fix. Thresholds in metres. */
fun accuracyLevelOf(accuracyMeters: Float?): AccuracyLevel = when {
    accuracyMeters == null || accuracyMeters.isNaN() || accuracyMeters < 0f -> AccuracyLevel.UNKNOWN
    accuracyMeters <= GOOD_ACCURACY_METERS -> AccuracyLevel.GOOD
    accuracyMeters <= FAIR_ACCURACY_METERS -> AccuracyLevel.FAIR
    else -> AccuracyLevel.POOR
}

/** Maps Android's provider name ("fused", "gps", "network") to our source. */
fun locationSourceForProvider(provider: String?): LocationSource = when (provider?.lowercase()) {
    "fused" -> LocationSource.FUSED
    "gps" -> LocationSource.GPS
    "network" -> LocationSource.NETWORK
    else -> LocationSource.UNKNOWN
}

const val GOOD_ACCURACY_METERS = 30f
const val FAIR_ACCURACY_METERS = 100f
