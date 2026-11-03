package com.safesphere.app.feature.community.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue
import android.util.AttributeSet
import android.view.View
import com.safesphere.app.R
import com.safesphere.app.core.ui.components.dp
import com.safesphere.app.core.ui.components.themeColor
import com.safesphere.app.feature.community.domain.CommunityPolicy.PlacedSignal
import com.safesphere.app.feature.community.domain.SignalGroup
import com.safesphere.app.feature.community.domain.SignalPrecision
import com.safesphere.app.feature.intelligence.domain.AreaOutlook
import com.safesphere.app.feature.intelligence.domain.InformationLevel
import com.safesphere.app.feature.intelligence.domain.IntelligencePolicy.RadarCell
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import com.google.android.material.R as MaterialR

/**
 * "Safety Radar" (Phase 6): the person in the centre, nearby signals by direction and distance (north up, rings at
 * 500 m, 1 km and 2 km). Drawn by the app itself, so no map tiles are downloaded: a map-tile server would learn
 * where the person is looking. Kind is shown by SHAPE as well as colour (triangle = incident, square = condition,
 * circle = safe haven); a signal known only to ~1 km has a soft halo instead of a sharp point.
 *
 * Decorative summary: the list below the radar carries every detail for screen readers (the screen sets one
 * content description for the whole radar).
 */
class SafetyRadarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var signals: List<PlacedSignal> = emptyList()
    private var cells: List<RadarCell> = emptyList()

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1).toFloat() }
    private val cellLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1).toFloat() }
    private val careOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val clip = Path()
    private val cellRect = RectF()
    // Phase 17F: sp, not dp - the labels follow the phone's text size; a halo in the card colour keeps them readable over cells and marks.
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = sp(12f); textAlign = Paint.Align.CENTER }
    private val haloTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(12f); textAlign = Paint.Align.CENTER; style = Paint.Style.STROKE; strokeWidth = dp(3).toFloat()
    }
    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(12f); textAlign = Paint.Align.LEFT; typeface = Typeface.DEFAULT_BOLD
    }
    private val markOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2).toFloat() }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()

    fun setSignals(placed: List<PlacedSignal>) {
        signals = placed
        invalidate()
    }

    /**
     * Phase 8: the ~1 km area cells around the person, drawn as cells (their real precision), never as a smooth heat
     * map. HOW MUCH IS KNOWN is the fill (hatched = nothing, dotted = little, plain = some or more), so "unknown" is a
     * visible state instead of an empty, reassuring space; an amber outline marks confirmed concerns. Empty = none.
     */
    fun setAreaCells(areaCells: List<RadarCell>) {
        cells = areaCells
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val size = min(width, dp(320))
        setMeasuredDimension(width, size)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(cx, cy) - dp(20)
        if (radius <= 0) return

        val outline = themeColor(MaterialR.attr.colorOutline)
        val label = themeColor(MaterialR.attr.colorOnSurfaceVariant)
        ringPaint.color = outline
        labelPaint.color = label

        if (cells.isNotEmpty()) drawCells(canvas, cx, cy, radius, outline)

        val surface = themeColor(MaterialR.attr.colorSurface)
        haloTextPaint.color = surface
        RINGS_METERS.forEach { meters ->
            val r = radius * meters / RANGE_METERS
            canvas.drawCircle(cx, cy, r, ringPaint)
            drawHaloText(canvas, ringLabel(meters), cx + r * 0.71f + dp(2), cy - r * 0.71f, labelPaint)
        }
        drawHaloText(canvas, context.getString(R.string.radar_north), cx, cy - radius - dp(2), labelPaint)

        val marker = dp(9).toFloat()
        // Where each signal is, then nudged apart where marks would overlap (drawing only; the list keeps the truth).
        val spots = signalSpots(cx, cy, radius)
        val drawn = spreadSpots(spots, marker, cx, cy, radius)
        // First every soft circle ("placed only to about 1 km"), so no later circle can wash out an earlier mark or number.
        signals.forEachIndexed { index, placed ->
            if (placed.signal.precision == SignalPrecision.AREA) {
                haloPaint.color = colorFor(placed.signal.category.group)
                haloPaint.alpha = 50
                canvas.drawCircle(spots[index].x, spots[index].y, radius * AREA_HALO_METERS / RANGE_METERS, haloPaint)
            }
        }
        signals.forEachIndexed { index, placed ->
            val x = drawn[index].x
            val y = drawn[index].y
            val color = colorFor(placed.signal.category.group)
            // A card-coloured outline separates a mark from the cells and rings under it.
            markOutlinePaint.color = surface
            drawShape(canvas, placed.signal.category.group, x, y, marker, outline = markOutlinePaint)
            fillPaint.color = color
            drawShape(canvas, placed.signal.category.group, x, y, marker)
            // The number matches the list below the radar ("1 · Harassment happened here").
            numberPaint.color = label
            drawHaloText(canvas, (index + 1).toString(), x + marker * 1.1f, y - marker * 0.9f, numberPaint)
        }

        // The person: a ring with a dot, in the primary colour.
        fillPaint.color = themeColor(MaterialR.attr.colorPrimary)
        canvas.drawCircle(cx, cy, dp(5).toFloat(), fillPaint)
        ringPaint.color = fillPaint.color
        canvas.drawCircle(cx, cy, dp(9).toFloat(), ringPaint)
    }

    /** Where each signal belongs on the radar (north up). A separate method, so onDraw itself allocates nothing new. */
    private fun signalSpots(cx: Float, cy: Float, radius: Float): List<RadarLayout.Mark> = signals.map { placed ->
        val distance = min(placed.distanceMeters, RANGE_METERS.toDouble()).toFloat()
        val angle = Math.toRadians(placed.bearingDegrees)
        RadarLayout.Mark(
            cx + (radius * distance / RANGE_METERS * sin(angle)).toFloat(),
            cy - (radius * distance / RANGE_METERS * cos(angle)).toFloat()
        )
    }

    private fun spreadSpots(spots: List<RadarLayout.Mark>, marker: Float, cx: Float, cy: Float, radius: Float) =
        RadarLayout.spread(spots, minGap = marker * 2.4f, centerX = cx, centerY = cy, limit = radius)

    private fun drawCells(canvas: Canvas, cx: Float, cy: Float, radius: Float, outline: Int) {
        val scale = radius / RANGE_METERS
        canvas.save()
        clip.reset()
        clip.addCircle(cx, cy, radius, Path.Direction.CW)
        canvas.clipPath(clip)
        cellLinePaint.color = outline
        careOutlinePaint.color = themeColor(R.attr.colorCaution)
        val step = dp(8).toFloat()
        cells.forEach { cell ->
            cellRect.set(cx + (cell.west * scale).toFloat(), cy - (cell.north * scale).toFloat(),
                cx + (cell.east * scale).toFloat(), cy - (cell.south * scale).toFloat())
            canvas.save()
            canvas.clipRect(cellRect)
            when (cell.information) {
                InformationLevel.NONE -> {
                    cellLinePaint.alpha = 70
                    var d = -cellRect.height()
                    while (d < cellRect.width()) {
                        canvas.drawLine(cellRect.left + d, cellRect.bottom, cellRect.left + d + cellRect.height(), cellRect.top, cellLinePaint)
                        d += step
                    }
                }
                InformationLevel.LITTLE -> {
                    fillPaint.color = outline
                    fillPaint.alpha = 90
                    var y = cellRect.top + step / 2
                    while (y < cellRect.bottom) {
                        var x = cellRect.left + step / 2
                        while (x < cellRect.right) {
                            canvas.drawCircle(x, y, dp(1).toFloat(), fillPaint)
                            x += step
                        }
                        y += step
                    }
                    fillPaint.alpha = 255
                }
                InformationLevel.SOME, InformationLevel.MORE -> Unit
            }
            canvas.restore()
            cellLinePaint.alpha = 60
            canvas.drawRect(cellRect, cellLinePaint)
            if (cell.outlook == AreaOutlook.TAKE_CARE || cell.outlook == AreaOutlook.TAKE_EXTRA_CARE) {
                careOutlinePaint.strokeWidth = dp(if (cell.outlook == AreaOutlook.TAKE_EXTRA_CARE) 3 else 2).toFloat()
                canvas.drawRect(cellRect, careOutlinePaint)
            }
        }
        cellLinePaint.alpha = 255
        canvas.restore()
    }

    /** Fills the shape, or - with [outline] - strokes it (used for the separating outline under a mark). */
    private fun drawShape(canvas: Canvas, group: SignalGroup, x: Float, y: Float, size: Float, outline: Paint? = null) {
        val paint = outline ?: fillPaint
        when (group) {
            SignalGroup.INCIDENT -> {
                path.reset()
                path.moveTo(x, y - size)
                path.lineTo(x + size, y + size * 0.8f)
                path.lineTo(x - size, y + size * 0.8f)
                path.close()
                canvas.drawPath(path, paint)
            }
            SignalGroup.CONDITION -> canvas.drawRect(x - size * 0.8f, y - size * 0.8f, x + size * 0.8f, y + size * 0.8f, paint)
            SignalGroup.HAVEN -> canvas.drawCircle(x, y, size * 0.9f, paint)
        }
    }

    private fun drawHaloText(canvas: Canvas, text: String, x: Float, y: Float, paint: Paint) {
        haloTextPaint.textAlign = paint.textAlign
        haloTextPaint.textSize = paint.textSize
        haloTextPaint.typeface = paint.typeface
        canvas.drawText(text, x, y, haloTextPaint)
        canvas.drawText(text, x, y, paint)
    }

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    /** Amber for concerns and conditions, green for havens. Red stays reserved for emergencies. */
    private fun colorFor(group: SignalGroup): Int = when (group) {
        SignalGroup.INCIDENT, SignalGroup.CONDITION -> themeColor(R.attr.colorCaution)
        SignalGroup.HAVEN -> themeColor(R.attr.colorSafe)
    }

    private fun ringLabel(meters: Int): String =
        if (meters >= 1000) context.getString(R.string.radar_km, meters / 1000) else context.getString(R.string.radar_m, meters)

    private companion object {
        const val RANGE_METERS = 2000f
        const val AREA_HALO_METERS = 450f
        val RINGS_METERS = listOf(500, 1000, 2000)
    }
}
