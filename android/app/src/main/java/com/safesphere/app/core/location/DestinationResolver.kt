package com.safesphere.app.core.location

/** Result of turning the destination text the person typed into a place. */
sealed interface DestinationLookup {
    /** [description] is the address line the map service returned: shown on screen so the person can check it. */
    data class Found(val point: GeoPoint, val description: String?) : DestinationLookup {
        override fun toString(): String = "Found(<redacted>)"
    }

    /** The map service answered but knows no such place (nearby). */
    data object NotFound : DestinationLookup

    /** No map service on this phone, no network, or it took too long. */
    data object Unavailable : DestinationLookup
}

/**
 * Finds the coordinates of a destination typed as text. Production: [GeocoderDestinationResolver] (the platform
 * Geocoder, which sends the text to the phone's map service, Google on most phones). Tests: a fake.
 * Never throws; a failure is a [DestinationLookup] value, and the walk then runs without route checks.
 */
interface DestinationResolver {
    /** [near] (optional) limits the search to the area around the person, which avoids far-away namesakes. */
    suspend fun resolve(query: String, near: GeoPoint?): DestinationLookup
}
