package com.safesphere.app.feature.intelligence.domain

import com.safesphere.app.core.location.GeoHash
import com.safesphere.app.core.location.GeoPoint
import com.safesphere.app.core.location.RouteGeometry
import com.safesphere.app.feature.community.domain.SignalCategory
import com.safesphere.app.feature.community.domain.SignalConfidence
import com.safesphere.app.feature.community.domain.SignalFreshness
import com.safesphere.app.feature.community.domain.SignalGroup
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * "Check the way" (Phase 8D, docs/PHASE8_DESIGN.md §15, §16), pure and unit-tested. Works on the phone so the
 * server never learns the route, the start or the destination: the phone asks for the ~5 km REGIONS the corridor
 * touches and briefs the walk itself.
 *
 * Corridor = the straight start -> destination line with the Safe Walk bubble's half-width, capped at
 * [MAX_HALF_WIDTH_METERS] (what is 2 km to the side is not "along the way"). It is a straight line, not the real
 * route, and the brief says so.
 *
 * The brief follows the two-channel rule (§16.1): only CONFIRMED signals (the only kind in a pack) are named as concerns;
 * unconfirmed reports, conflicts and how much is unknown are UNCERTAINTY, shown but never turned into a concern.
 * Places to go for help never cancel a concern. It never ranks, never says "safe", and changes nothing in the walk.
 */
object CorridorBriefPolicy {

    const val MAX_HALF_WIDTH_METERS = 500.0
    const val MAX_LENGTH_METERS = 20_000.0
    const val MAX_REGIONS = 4
    private const val STEP_METERS = 100.0
    private const val METERS_PER_DEGREE = 6_371_000.0 * Math.PI / 180.0

    data class Corridor(val start: GeoPoint, val destination: GeoPoint, val halfWidthMeters: Double) {
        override fun toString() = "Corridor(<redacted>, halfWidth=$halfWidthMeters)"
    }

    /** [bubbleHalfWidthMeters] = MovementPolicy.corridorHalfWidthMeters(start, destination). */
    fun corridor(start: GeoPoint, destination: GeoPoint, bubbleHalfWidthMeters: Double) =
        Corridor(start, destination, min(bubbleHalfWidthMeters, MAX_HALF_WIDTH_METERS))

    sealed interface Plan {
        /** The 5-character regions to download (the only thing the server will see). */
        data class Regions(val cells: List<String>) : Plan

        data object TooLong : Plan
    }

    fun regionsFor(corridor: Corridor): Plan {
        if (RouteGeometry.distanceMeters(corridor.start, corridor.destination) > MAX_LENGTH_METERS) return Plan.TooLong
        val regions = samples(corridor, withEdges = true).map { GeoHash.encode(it, GeoHash.REGION) }.distinct()
        return if (regions.size > MAX_REGIONS) Plan.TooLong else Plan.Regions(regions)
    }

    enum class Coverage { MOSTLY_UNKNOWN, PARTLY_KNOWN, MOSTLY_KNOWN }

    enum class Havens { NONE, ONE, SEVERAL }

    /** What is known around the way. Categories are night-ordered when [night] is true. */
    data class CorridorBrief(
        val concernCategories: List<SignalCategory>,
        val strongConcern: Boolean,
        val incidentToday: Boolean,
        val unconfirmedNearby: Boolean,
        val recurringNearby: Boolean,
        val conflicting: Boolean,
        val havens: Havens,
        val coverage: Coverage
    ) {
        val hasConfirmedConcerns: Boolean get() = concernCategories.isNotEmpty()
    }

    sealed interface Result {
        data class Ready(val brief: CorridorBrief) : Result

        /** A pack was incomplete or not understood: no brief rather than a brief that might miss a concern. */
        data object Incomplete : Result
    }

    fun brief(corridor: Corridor, packs: List<RegionPack>, night: Boolean): Result {
        if (packs.isEmpty() || packs.any { !it.complete }) return Result.Incomplete
        val signals = packs.flatMap { it.signals }.filter { inCorridor(corridor, GeoHash.centre(it.cell)) }
        val areas = packs.flatMap { it.areas }.associateBy { it.cell }

        val concerns = signals.filter { it.category.group != SignalGroup.HAVEN }
        val categories = concerns.map { it.category }.distinct().sortedBy { it.ordinal }
        val corridorAreas = samples(corridor, withEdges = true).map { GeoHash.encode(it, GeoHash.AREA) }.distinct()
            .mapNotNull { areas[it] }
        val lineCells = samples(corridor, withEdges = false).map { GeoHash.encode(it, GeoHash.AREA) }.distinct()
        val unknown = lineCells.count { cell -> areas[cell].let { it == null || it.information == InformationLevel.NONE } }
        val unknownShare = unknown.toDouble() / lineCells.size
        val havenCount = signals.count { it.category.group == SignalGroup.HAVEN }

        return Result.Ready(
            CorridorBrief(
                concernCategories = IntelligencePolicy.orderForTime(categories, night),
                strongConcern = concerns.any { it.confidence == SignalConfidence.HIGH },
                incidentToday = concerns.any { it.category.group == SignalGroup.INCIDENT && it.freshness == SignalFreshness.TODAY },
                unconfirmedNearby = corridorAreas.any { PictureReason.UNCONFIRMED_REPORTS in it.reasons },
                recurringNearby = corridorAreas.any { PictureReason.RECURRING_CONCERNS in it.reasons },
                conflicting = concerns.any { it.disputed } || contradiction(signals) || corridorAreas.any { it.conflicting },
                havens = when (havenCount) { 0 -> Havens.NONE; 1 -> Havens.ONE; else -> Havens.SEVERAL },
                coverage = when {
                    unknownShare >= 2.0 / 3 -> Coverage.MOSTLY_UNKNOWN
                    unknownShare >= 1.0 / 3 -> Coverage.PARTLY_KNOWN
                    else -> Coverage.MOSTLY_KNOWN
                }
            )
        )
    }

    fun inCorridor(corridor: Corridor, point: GeoPoint): Boolean =
        RouteGeometry.distanceToSegmentMeters(point, corridor.start, corridor.destination) <= corridor.halfWidthMeters

    private fun contradiction(signals: List<RegionSignal>): Boolean {
        val wellLit = signals.filter { it.category == SignalCategory.WELL_LIT_BUSY }.map { it.cell }.toSet()
        return signals.any { it.cell in wellLit &&
            (it.category == SignalCategory.POOR_LIGHTING || it.category == SignalCategory.ISOLATED_STRETCH) }
    }

    /**
     * Points every ~100 m along the line; with [withEdges] also along both sides at the half-width and around both
     * ends (the corridor's round caps), so every region and area the corridor touches is covered.
     */
    private fun samples(corridor: Corridor, withEdges: Boolean): List<GeoPoint> {
        val start = corridor.start
        val end = corridor.destination
        val length = RouteGeometry.distanceMeters(start, end)
        val steps = maxOf(1, (length / STEP_METERS).toInt())
        val scaleX = cos(Math.toRadians(start.latitude)) * METERS_PER_DEGREE
        val dx = (end.longitude - start.longitude) * scaleX
        val dy = (end.latitude - start.latitude) * METERS_PER_DEGREE
        val norm = maxOf(1e-9, kotlin.math.hypot(dx, dy))
        val px = -dy / norm * corridor.halfWidthMeters // perpendicular, in metres
        val py = dx / norm * corridor.halfWidthMeters
        val points = mutableListOf<GeoPoint>()
        for (i in 0..steps) {
            val t = i.toDouble() / steps
            val centre = GeoPoint(start.latitude + (end.latitude - start.latitude) * t,
                start.longitude + (end.longitude - start.longitude) * t)
            points += centre
            if (withEdges) {
                points += offset(centre, px, py, scaleX)
                points += offset(centre, -px, -py, scaleX)
            }
        }
        if (withEdges) {
            for (k in 0 until 8) {
                val a = Math.toRadians(k * 45.0)
                val ex = sin(a) * corridor.halfWidthMeters
                val ey = cos(a) * corridor.halfWidthMeters
                points += offset(start, ex, ey, scaleX)
                points += offset(end, ex, ey, scaleX)
            }
        }
        return points
    }

    private fun offset(point: GeoPoint, eastMeters: Double, northMeters: Double, scaleX: Double) =
        GeoPoint(point.latitude + northMeters / METERS_PER_DEGREE, point.longitude + eastMeters / scaleX)
}
