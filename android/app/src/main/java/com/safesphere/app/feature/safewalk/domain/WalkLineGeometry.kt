package com.safesphere.app.feature.safewalk.domain

import com.safesphere.app.core.location.GeoPoint
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max

/**
 * Phase 17F: where the pieces of the Safe Walk line go on the screen. Pure Kotlin, unit-tested; the view only draws.
 *
 * The line is the STRAIGHT line from where the walk started to the place the person named - the same line the Safety
 * Bubble measures against (`MovementPolicy`) - plus the band around it and the person's current position. It is not a
 * route, not a recommended way and says nothing about safety; nothing is stored (it is computed from the walk's
 * in-memory state each time) and no trail of past positions exists to draw.
 *
 * All outputs are in a unit square (0..1, y pointing DOWN, north up) with a uniform scale, so shapes are not distorted.
 */
object WalkLineGeometry {

    data class Spot(val x: Float, val y: Float)

    /**
     * @param bandHalfWidth half-width of the Safety Bubble band, in unit-square units
     * @param position the current position; null when there is no fix yet
     * @param alongFraction how far along the line the position projects, 0 = start, 1 = destination (clamped)
     */
    data class Layout(
        val start: Spot,
        val destination: Spot,
        val position: Spot?,
        val bandHalfWidth: Float,
        val alongFraction: Float?
    )

    private const val METERS_PER_DEGREE = 6_371_000.0 * Math.PI / 180.0
    private const val MARGIN = 0.12

    /**
     * A walk shorter than this has no line worth drawing: the arrival hint (80 m) already covers it, start and destination
     * would sit on top of each other and the band would fill the whole picture.
     */
    const val MIN_LINE_METERS = 100.0

    /** Null when there is no line to draw: no start yet, or start and destination are (nearly) the same place. */
    fun layout(start: GeoPoint?, destination: GeoPoint?, current: GeoPoint?, bandHalfWidthMeters: Double): Layout? {
        if (start == null || destination == null) return null
        val scaleX = cos(Math.toRadians(start.latitude)) * METERS_PER_DEGREE
        fun x(p: GeoPoint) = (p.longitude - start.longitude) * scaleX
        fun y(p: GeoPoint) = (p.latitude - start.latitude) * METERS_PER_DEGREE

        val points = listOfNotNull(start, destination, current)
        val minX = points.minOf(::x)
        val maxX = points.maxOf(::x)
        val minY = points.minOf(::y)
        val maxY = points.maxOf(::y)
        val lineLength = hypot(x(destination), y(destination))
        if (lineLength < MIN_LINE_METERS) return null

        // The span holds the whole band: it reaches one half-width beyond the start, the destination and you on every side
        // (round ends), so the shaded band is never cut off at the edge of the picture.
        val span = max(maxX - minX, maxY - minY) + 2 * bandHalfWidthMeters.coerceAtLeast(0.0)
        val usable = 1.0 - 2 * MARGIN
        val scale = usable / span
        val centerX = (minX + maxX) / 2
        val centerY = (minY + maxY) / 2

        fun spot(p: GeoPoint) = Spot(
            (0.5 + (x(p) - centerX) * scale).toFloat(),
            (0.5 - (y(p) - centerY) * scale).toFloat() // north up: larger latitude is higher on the screen
        )

        val along = current?.let {
            val ex = x(destination)
            val ey = y(destination)
            (((x(it) * ex + y(it) * ey) / (lineLength * lineLength))).coerceIn(0.0, 1.0).toFloat()
        }
        return Layout(
            start = spot(start),
            destination = spot(destination),
            position = current?.let(::spot),
            bandHalfWidth = (bandHalfWidthMeters.coerceAtLeast(0.0) * scale).toFloat(),
            alongFraction = along
        )
    }
}
