package com.safesphere.app.feature.emergency.data.remote

import com.safesphere.app.feature.emergency.domain.EmergencyLocationPoint

// Mirrors the JSON of POST /api/emergencies/{id}/locations (see docs/API.md). No user or emergency id in
// the body: the emergency is in the URL and the server checks it belongs to the token's user.

data class LocationBatchRequestDto(val points: List<LocationPointDto>) {
    override fun toString(): String = "LocationBatchRequestDto(points=${points.size})"
}

data class LocationPointDto(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
    val source: String,
    val batteryPercent: Int?,
    val recordedAt: String
) {
    /** Coordinates are never written to logs. */
    override fun toString(): String = "LocationPointDto(location=<redacted>, recordedAt=$recordedAt)"

    companion object {
        fun from(point: EmergencyLocationPoint) = LocationPointDto(
            latitude = point.fix.latitude,
            longitude = point.fix.longitude,
            accuracyMeters = point.fix.accuracyMeters,
            source = point.fix.source.name,
            batteryPercent = point.batteryPercent,
            recordedAt = point.fix.recordedAt.toString()
        )
    }
}

data class LocationUploadResponseDto(val received: Int, val stored: Int)
