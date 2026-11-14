package com.safesphere.app.feature.social.ui

import com.safesphere.app.core.location.GeoHash
import com.safesphere.app.core.location.LocationProvider
import com.safesphere.app.core.location.PreciseFix
import com.safesphere.app.core.location.preciseFix

/**
 * Phase 18A-2 (trust rule A3): the cell the phone may send with "Been here", so the server can note that the person was at the
 * place. Everything here is decided ON THE PHONE and errs towards sending nothing:
 *
 *  - It never asks for a permission. Without precise location already allowed, or with location off, nothing is looked up.
 *  - It uses the one shared search ([preciseFix]) and its 100 m rule: a rough position is never used.
 *  - It answers with a cell ONLY when that position is in the place's own ~150 m cell or one of the cells around it. Tapped
 *    from anywhere else (home, work), the phone sends nothing about where the person is: the server never learns it.
 *
 * The position itself never leaves the phone and nothing is stored; the server keeps one yes/no and drops the cell.
 */
object PresenceCell {

    /** Shorter than a report's search: this is a quiet extra, not something the person waits for. */
    const val WINDOW_MILLIS = 10_000L

    /**
     * True when ([latitude], [longitude]) lies in [placeCell] or in one of the eight cells of the same size around it. A cell that
     * is not a well-formed block cell is never "near" (nothing is sent).
     */
    fun near(placeCell: String?, latitude: Double, longitude: Double): Boolean {
        if (!GeoHash.isValid(placeCell, GeoHash.BLOCK)) return false
        val b = GeoHash.bounds(placeCell!!)
        val height = b.latMax - b.latMin
        val width = b.lngMax - b.lngMin
        return latitude >= b.latMin - height && latitude < b.latMax + height &&
            longitude >= b.lngMin - width && longitude < b.lngMax + width
    }

    /**
     * The phone's block cell if it is at the place right now, otherwise null. [placeCell] is asked only after a good position
     * exists, so a phone without one makes no extra request.
     */
    suspend fun ifAt(provider: LocationProvider, placeCell: suspend () -> String?): String? {
        val fix = (provider.preciseFix(windowMillis = WINDOW_MILLIS) as? PreciseFix.Good)?.fix ?: return null
        val cell = placeCell() ?: return null
        return if (near(cell, fix.latitude, fix.longitude)) GeoHash.encode(fix.latitude, fix.longitude, GeoHash.BLOCK) else null
    }
}
