package com.safesphere.app.feature.intelligence.domain

import com.safesphere.app.core.location.GeoHash
import com.safesphere.app.core.location.GeoPoint
import com.safesphere.app.core.location.RouteGeometry
import com.safesphere.app.feature.community.domain.CommunityPolicy
import com.safesphere.app.feature.community.domain.SignalCategory
import kotlin.math.cos

/**
 * Phase 8 display rules on the phone (pure, unit-tested). The SERVER decides the picture (rules-1); the phone only
 * decides how to present it:
 *  - after dark (by the phone's own clock, never sent anywhere) conditions that matter at night come first;
 *  - where the strongest concern is, as a compass direction and a rounded distance to its ~1 km cell;
 *  - the nine area cells as rectangles around the person, for the radar.
 */
object IntelligencePolicy {

    /** Conditions that matter most after dark (a property of the category, not of any report's time). */
    val NIGHT_RELEVANT: Set<SignalCategory> = setOf(SignalCategory.POOR_LIGHTING, SignalCategory.ISOLATED_STRETCH)

    /** 19:00-05:59 local time. A display choice only. */
    fun isNight(localHour: Int): Boolean = localHour >= 19 || localHour < 6

    /** Night-relevant categories first after dark (stable otherwise). */
    fun orderForTime(categories: List<SignalCategory>, night: Boolean): List<SignalCategory> =
        if (night) categories.sortedBy { if (it in NIGHT_RELEVANT) 0 else 1 } else categories

    enum class Compass { N, NE, E, SE, S, SW, W, NW;
        companion object {
            fun fromBearing(degrees: Double): Compass = entries[(((degrees % 360 + 360) % 360 + 22.5) / 45).toInt() % 8]
        }
    }

    /** Where a ~1 km cell is from the person. [compass] null = the person's own cell ("around you"). */
    data class Placement(val approximateMeters: Int, val compass: Compass?)

    fun place(origin: GeoPoint, areaCell: String): Placement? {
        if (!GeoHash.isValid(areaCell, GeoHash.AREA)) return null
        if (GeoHash.encode(origin, GeoHash.AREA) == areaCell) return Placement(0, null)
        val centre = GeoHash.centre(areaCell)
        return Placement(
            CommunityPolicy.approximateMeters(RouteGeometry.distanceMeters(origin, centre)),
            Compass.fromBearing(CommunityPolicy.bearing(origin, centre))
        )
    }

    /**
     * One area cell as a rectangle in metres around the person (east and north positive), for drawing. Coarse on
     * purpose: the cell is the precision the server gave, nothing finer is drawn.
     */
    data class RadarCell(
        val west: Double,
        val east: Double,
        val south: Double,
        val north: Double,
        val information: InformationLevel,
        val outlook: AreaOutlook
    )

    fun radarCells(origin: GeoPoint, picture: AreaPicture): List<RadarCell> = picture.areas.mapNotNull { area ->
        if (!GeoHash.isValid(area.cell, GeoHash.AREA)) return@mapNotNull null
        val b = GeoHash.bounds(area.cell)
        val scaleX = cos(Math.toRadians(origin.latitude)) * METERS_PER_DEGREE
        RadarCell(
            west = (b.lngMin - origin.longitude) * scaleX,
            east = (b.lngMax - origin.longitude) * scaleX,
            south = (b.latMin - origin.latitude) * METERS_PER_DEGREE,
            north = (b.latMax - origin.latitude) * METERS_PER_DEGREE,
            information = area.information,
            outlook = area.outlook
        )
    }

    private const val METERS_PER_DEGREE = 6_371_000.0 * Math.PI / 180.0
}
