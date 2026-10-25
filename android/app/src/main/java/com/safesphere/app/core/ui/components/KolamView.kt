package com.safesphere.app.core.ui.components

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.graphics.PathParser
import com.google.android.material.color.MaterialColors

/**
 * Phase 13F: the kolam of the empty states. A kolam is drawn at the door every morning for whoever comes next, which is
 * what an empty Kurinji screen asks for. The line draws itself once when first shown (~0.9 s), then rests; with
 * animations off it is simply there. Decorative for TalkBack (the empty-state words carry the meaning).
 */
class KolamView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = MaterialColors.getColor(this@KolamView, androidx.appcompat.R.attr.colorPrimary)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(this@KolamView, com.google.android.material.R.attr.colorOnSurfaceVariant)
    }
    private val scaled = Path()
    private val partial = Path()
    private var measure: PathMeasure? = null
    private var progress = 1f
    private var drawn = false
    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        scale = minOf(w, h) / BOX
        offsetX = (w - BOX * scale) / 2f
        offsetY = (h - BOX * scale) / 2f
        val matrix = Matrix().apply { setScale(scale, scale); postTranslate(offsetX, offsetY) }
        scaled.reset()
        LINE.transform(matrix, scaled)
        measure = PathMeasure(scaled, false)
        linePaint.strokeWidth = 1.6f * scale
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (drawn || !ValueAnimator.areAnimatorsEnabled()) {
            progress = 1f
            return
        }
        drawn = true
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
        }.start()
    }

    override fun onDraw(canvas: Canvas) {
        for (dot in DOTS) canvas.drawCircle(offsetX + dot.first * scale, offsetY + dot.second * scale, 2f * scale, dotPaint)
        if (progress >= 1f) {
            canvas.drawPath(scaled, linePaint)
            return
        }
        // A kolam is one continuous line: draw each contour in turn up to the current length.
        val m = measure ?: return
        partial.reset()
        var total = 0f
        var lengths = 0f
        m.setPath(scaled, false)
        do { lengths += m.length } while (m.nextContour())
        val target = lengths * progress
        m.setPath(scaled, false)
        do {
            val take = (target - total).coerceAtMost(m.length)
            if (take > 0f) m.getSegment(0f, take, partial, true)
            total += m.length
        } while (total < target && m.nextContour())
        canvas.drawPath(partial, linePaint)
    }

    private companion object {
        const val BOX = 60f
        val DOTS = listOf(20f to 20f, 40f to 20f, 20f to 40f, 40f to 40f, 30f to 30f)
        val LINE: Path = PathParser.createPathFromPathData(
            "M30,8C44,8 52,16 52,30S44,52 30,52 8,44 8,30 16,8 30,8Z" +
                "M30,8C22,16 22,22 30,30S38,44 30,52" +
                "M30,8C38,16 38,22 30,30S22,44 30,52" +
                "M8,30C16,22 22,22 30,30S44,38 52,30" +
                "M8,30C16,38 22,38 30,30S44,22 52,30"
        )
    }
}
