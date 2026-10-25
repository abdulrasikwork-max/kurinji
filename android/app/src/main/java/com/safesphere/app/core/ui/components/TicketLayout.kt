package com.safesphere.app.core.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.widget.LinearLayout
import com.google.android.material.color.MaterialColors
import com.safesphere.app.R

/**
 * Phase 13F: the ticket that carries "For the next person" (docs/PHASE13F_PLAN.md §2): a soft-bloom slip with a notch
 * on each side, like a bus ticket, and a dashed line under its first child (the label). A plain vertical LinearLayout
 * otherwise, so layouts put a label and the tip text inside it.
 */
class TicketLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val notch = resources.getDimension(R.dimen.ticket_notch_radius)
    private val corner = resources.getDimension(R.dimen.space_sm)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(this@TicketLayout, com.google.android.material.R.attr.colorPrimaryContainer)
    }
    private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(this@TicketLayout, androidx.appcompat.R.attr.colorPrimary)
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
        pathEffect = DashPathEffect(floatArrayOf(4 * resources.displayMetrics.density, 3 * resources.displayMetrics.density), 0f)
    }
    private val shape = Path()
    private val rect = RectF()

    init {
        orientation = VERTICAL
        setWillNotDraw(false)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        shape.reset()
        rect.set(0f, 0f, w.toFloat(), h.toFloat())
        shape.addRoundRect(rect, corner, corner, Path.Direction.CW)
        val cut = Path().apply {
            addCircle(0f, h / 2f, notch, Path.Direction.CW)
            addCircle(w.toFloat(), h / 2f, notch, Path.Direction.CW)
        }
        shape.op(cut, Path.Op.DIFFERENCE)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawPath(shape, fill)
        val label = if (childCount > 1) getChildAt(0) else null
        if (label != null && label.visibility == VISIBLE) {
            val y = label.bottom + resources.displayMetrics.density * 3
            canvas.drawLine(paddingLeft.toFloat(), y, (width - paddingRight).toFloat(), y, dash)
        }
        super.onDraw(canvas)
    }
}
