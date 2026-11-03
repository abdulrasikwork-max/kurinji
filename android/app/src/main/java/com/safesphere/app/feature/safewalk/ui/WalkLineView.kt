package com.safesphere.app.feature.safewalk.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import com.safesphere.app.R
import com.safesphere.app.core.ui.components.dp
import com.safesphere.app.core.ui.components.themeColor
import com.safesphere.app.feature.safewalk.domain.WalkLineGeometry
import kotlin.math.min
import com.google.android.material.R as MaterialR

/**
 * Phase 17F: the Safe Walk line, drawn by the app itself (no map tiles, so no map server learns where the person is):
 * where the walk started, the place that was named, the straight line between them, the band the route checks watch, and
 * the current position. North is up. It is the same straight line the Safety Bubble measures against - not a route and
 * not a recommended way. The shapes differ (square = start, diamond = destination, ring = you) and every mark has a word,
 * so nothing depends on colour. Decorative for TalkBack: the screen sets one description of the whole view.
 */
class WalkLineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var layout: WalkLineGeometry.Layout? = null
    private var outside = false

    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(2).toFloat(); pathEffect = DashPathEffect(floatArrayOf(dp(8).toFloat(), dp(6).toFloat()), 0f)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2).toFloat() }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(12f); typeface = Typeface.DEFAULT_BOLD
    }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(12f); typeface = Typeface.DEFAULT_BOLD; style = Paint.Style.STROKE; strokeWidth = dp(3).toFloat()
    }
    private val diamond = android.graphics.Path()

    /** [layout] = null hides the line. [outsideBand] is only a wording aid for the labels; it never changes a check-in. */
    fun setLine(layout: WalkLineGeometry.Layout?, outsideBand: Boolean) {
        this.layout = layout
        this.outside = outsideBand
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, min(width, dp(260)))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val layout = layout ?: return
        val side = min(width, height).toFloat()
        val left = (width - side) / 2f
        val top = (height - side) / 2f
        fun px(x: Float) = left + x * side
        fun py(y: Float) = top + y * side

        val outline = themeColor(MaterialR.attr.colorOutline)
        val ink = themeColor(MaterialR.attr.colorOnSurface)
        val primary = themeColor(MaterialR.attr.colorPrimary)
        val surface = themeColor(MaterialR.attr.colorSurface)

        val sx = px(layout.start.x)
        val sy = py(layout.start.y)
        val dx = px(layout.destination.x)
        val dy = py(layout.destination.y)

        // The band the route checks watch: a capsule of the Safety Bubble's half-width around the straight line.
        bandPaint.color = outline
        bandPaint.alpha = 55
        bandPaint.strokeWidth = (layout.bandHalfWidth * side * 2f).coerceAtLeast(dp(6).toFloat())
        canvas.drawLine(sx, sy, dx, dy, bandPaint)

        linePaint.color = ink
        canvas.drawLine(sx, sy, dx, dy, linePaint)

        val mark = dp(8).toFloat()
        // Start: a square. Destination: a diamond.
        fillPaint.color = surface
        canvas.drawRect(sx - mark, sy - mark, sx + mark, sy + mark, fillPaint)
        strokePaint.color = ink
        canvas.drawRect(sx - mark, sy - mark, sx + mark, sy + mark, strokePaint)
        diamond.reset()
        diamond.moveTo(dx, dy - mark * 1.3f)
        diamond.lineTo(dx + mark * 1.3f, dy)
        diamond.lineTo(dx, dy + mark * 1.3f)
        diamond.lineTo(dx - mark * 1.3f, dy)
        diamond.close()
        fillPaint.color = ink
        canvas.drawPath(diamond, fillPaint)

        // The two end labels go on opposite sides (start away from the destination), so they can never print over each other.
        val destinationIsRight = dx >= sx
        drawLabel(canvas, context.getString(R.string.walk_line_start), sx, sy, mark, surface, ink, onRight = !destinationIsRight)
        drawLabel(canvas, context.getString(R.string.walk_line_destination), dx, dy, mark * 1.3f, surface, ink, onRight = destinationIsRight)

        layout.position?.let { position ->
            val x = px(position.x)
            val y = py(position.y)
            fillPaint.color = primary
            canvas.drawCircle(x, y, dp(5).toFloat(), fillPaint)
            strokePaint.color = primary
            canvas.drawCircle(x, y, dp(10).toFloat(), strokePaint)
            // "You" sits above the mark, centred, where neither end label is.
            val text = context.getString(if (outside) R.string.walk_line_you_outside else R.string.walk_line_you)
            val tx = (x - textPaint.measureText(text) / 2f).coerceIn(dp(2).toFloat(), width - textPaint.measureText(text) - dp(2))
            val ty = (y - dp(16)).coerceAtLeast(textPaint.textSize)
            haloPaint.color = surface
            textPaint.color = primary
            canvas.drawText(text, tx, ty, haloPaint)
            canvas.drawText(text, tx, ty, textPaint)
        }
    }

    /** A word beside a mark, on the side with more room, with a halo in the card colour so it reads over the band. */
    private fun drawLabel(
        canvas: Canvas, text: String, x: Float, y: Float, offset: Float, halo: Int, color: Int, onRight: Boolean
    ) {
        val textWidth = textPaint.measureText(text)
        val wanted = if (onRight) x + offset + dp(4) else x - offset - dp(4) - textWidth
        val tx = wanted.coerceIn(dp(2).toFloat(), width - textWidth - dp(2))
        val ty = (y + textPaint.textSize / 3f).coerceIn(textPaint.textSize, height - dp(4).toFloat())
        haloPaint.color = halo
        textPaint.color = color
        canvas.drawText(text, tx, ty, haloPaint)
        canvas.drawText(text, tx, ty, textPaint)
    }

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)
}
