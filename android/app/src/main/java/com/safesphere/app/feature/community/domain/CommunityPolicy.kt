package com.safesphere.app.feature.community.domain

import com.safesphere.app.core.location.GeoPoint
import com.safesphere.app.core.location.LocationAccess
import com.safesphere.app.core.location.RouteGeometry
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt

/** Pure Phase 6 rules on the phone (unit-tested in CommunityPolicyTest). */
object CommunityPolicy {

    /** A report is placed in a ~150 m cell, so the fix must be at least that good. */
    const val MAX_REPORT_ACCURACY_METERS = 100f

    const val OUTBOX_MAX = 10

    /** An unsent report older than this says little about the place now; it is dropped, not sent late. */
    const val OUTBOX_MAX_AGE_MILLIS = 24L * 60 * 60 * 1000

    enum class ReportReadiness { READY, NEEDS_PRECISE_LOCATION, LOCATION_TOO_ROUGH }

    /**
     * Looking at signals works with approximate location (it only needs the ~1 km area). Reporting needs precise
     * location and a good fix: a report placed in the wrong block would mislead everyone who reads it.
     */
    fun reportReadiness(access: LocationAccess, accuracyMeters: Float?): ReportReadiness = when {
        access != LocationAccess.PRECISE -> ReportReadiness.NEEDS_PRECISE_LOCATION
        accuracyMeters == null || accuracyMeters.isNaN() || accuracyMeters > MAX_REPORT_ACCURACY_METERS ->
            ReportReadiness.LOCATION_TOO_ROUGH
        else -> ReportReadiness.READY
    }

    /** Adds a report to the outbox: the same place and category only once, newest [OUTBOX_MAX] kept. */
    fun addToOutbox(outbox: List<PendingReport>, report: PendingReport): List<PendingReport> =
        (outbox.filterNot { it.category == report.category && it.blockCell == report.blockCell } + report)
            .takeLast(OUTBOX_MAX)

    fun sendable(outbox: List<PendingReport>, nowMillis: Long): List<PendingReport> =
        outbox.filter { nowMillis - it.createdAtMillis in 0..OUTBOX_MAX_AGE_MILLIS }

    /** A signal placed relative to the person: distance and compass bearing (0 = north, clockwise). */
    data class PlacedSignal(val signal: AreaSignal, val distanceMeters: Double, val bearingDegrees: Double)

    fun place(signals: List<AreaSignal>, from: GeoPoint): List<PlacedSignal> =
        signals.map { signal ->
            PlacedSignal(signal, RouteGeometry.distanceMeters(from, signal.centre), bearing(from, signal.centre))
        }.sortedBy { it.distanceMeters }

    fun bearing(from: GeoPoint, to: GeoPoint): Double {
        val dx = (to.longitude - from.longitude) * cos(Math.toRadians(from.latitude))
        val dy = to.latitude - from.latitude
        if (dx == 0.0 && dy == 0.0) return 0.0
        val degrees = Math.toDegrees(atan2(dx, dy))
        return (degrees + 360.0) % 360.0
    }

    /**
     * A calm summary instead of a feed. Counted per kind for the whole answer; the nearest safe haven is the most
     * useful single fact when someone feels unsafe.
     */
    data class AreaBrief(val incidents: Int, val conditions: Int, val havens: Int, val nearestHavenMeters: Int?) {
        val isEmpty: Boolean get() = incidents == 0 && conditions == 0 && havens == 0
    }

    fun brief(placed: List<PlacedSignal>): AreaBrief = AreaBrief(
        incidents = placed.count { it.signal.category.group == SignalGroup.INCIDENT },
        conditions = placed.count { it.signal.category.group == SignalGroup.CONDITION },
        havens = placed.count { it.signal.category.group == SignalGroup.HAVEN },
        nearestHavenMeters = placed.firstOrNull { it.signal.category.group == SignalGroup.HAVEN }
            ?.let { approximateMeters(it.distanceMeters) }
    )

    /**
     * Distances are shown rounded (50 m steps, 100 m from 1 km): the cells are ~150 m, so more digits would claim a
     * precision nobody has.
     */
    fun approximateMeters(meters: Double): Int = when {
        meters < 1000 -> ((meters / 50).roundToInt() * 50).coerceAtLeast(50)
        else -> (meters / 100).roundToInt() * 100
    }

    /**
     * Whether the person may still answer this signal: one answer per person, never their own, and not after
     * flagging it (the server refuses all of these too).
     */
    fun canAnswer(signal: AreaSignal): Boolean = signal.yourContribution == null && !signal.youFlagged

    /** Phase 7: one flag per person, never on a signal they added to (they withdraw their own answer instead). */
    fun canFlag(signal: AreaSignal): Boolean = signal.yourContribution == null && !signal.youFlagged
}
