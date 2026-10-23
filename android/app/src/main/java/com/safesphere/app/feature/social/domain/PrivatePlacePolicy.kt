package com.safesphere.app.feature.social.domain

import com.safesphere.app.core.location.GeoHash

/** The kinds of private place a person can mark (stored on the phone only, encrypted). */
enum class PrivatePlaceKind { HOME, HOSTEL, WORK, OTHER }

/** A private place: only its ~150 m cell and its kind. Never uploaded. */
data class PrivatePlace(val id: String, val kind: PrivatePlaceKind, val cell: String) {
    override fun toString() = "PrivatePlace(id=$id, kind=$kind, cell=<hidden>)"
}

/**
 * Phase 13B (design §4.2): private places stop posts from revealing home, hostel or work. A place is "private" when its
 * cell is a private cell or one of the 8 cells around it (~450 m x 450 m in all), so a café next door cannot reveal the
 * street either. Near a private place the composer offers only City or No place, and a new public place there is
 * refused. Pure.
 */
object PrivatePlacePolicy {

    fun isPrivate(cell: String?, privateCells: Collection<String>): Boolean {
        if (cell == null || privateCells.isEmpty() || !GeoHash.isValid(cell, GeoHash.BLOCK)) return false
        val around = neighbourhood(cell)
        return privateCells.any { it in around }
    }

    /** The cell and its (up to) 8 neighbours of the same size, as the backend's GeoCell.neighbourhood. */
    fun neighbourhood(cell: String): Set<String> {
        val b = GeoHash.bounds(cell)
        val height = b.latMax - b.latMin
        val width = b.lngMax - b.lngMin
        val lat = (b.latMin + b.latMax) / 2
        val lng = (b.lngMin + b.lngMax) / 2
        val cells = linkedSetOf<String>()
        for (dLat in -1..1) {
            val nLat = lat + dLat * height
            if (nLat < -90 || nLat > 90) continue
            for (dLng in -1..1) {
                var nLng = lng + dLng * width
                if (nLng > 180) nLng -= 360
                if (nLng < -180) nLng += 360
                cells += GeoHash.encode(nLat, nLng, cell.length)
            }
        }
        return cells
    }
}
