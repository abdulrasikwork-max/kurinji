package com.safesphere.app.core.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.View
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.color.MaterialColors
import com.safesphere.app.R
import kotlin.math.min

/**
 * Phase 13F: the postmark that says WHERE and WHEN on every experience (docs/PHASE13F_PLAN.md §2). It shows only what
 * the author chose to reveal: a place name on the ring, a grid reference for an area, a city, or a blank cancellation
 * for "no place". Drawn in violet "office-stamp" ink at a slant. Decorative for TalkBack: the card says the same words.
 *
 * Layout is computed when the content or the size changes; [onDraw] allocates nothing.
 */
class PostmarkView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** [ring] runs along the top, [lower] along the bottom, [centre] sits in the middle; [blank] = no place shown. */
    data class Content(val ring: String, val lower: String, val centre: String?, val blank: Boolean = false)

    var content: Content? = null
        set(value) {
            if (field == value) return
            field = value
            prepare()
            invalidate()
        }

    private val ink = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary)
    private val density = resources.displayMetrics.density

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = ink
        alpha = 225
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ink
        typeface = ResourcesCompat.getFont(context, R.font.kurinji_sans_medium)
        letterSpacing = 0.12f
        isFakeBoldText = false
    }
    private val centrePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ink
        typeface = ResourcesCompat.getFont(context, R.font.kurinji_display)
        textAlign = Paint.Align.CENTER
    }
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = ink
        alpha = 200
        strokeCap = Paint.Cap.ROUND
    }

    private val topArc = Path()
    private val bottomArc = Path()
    private val waves = Path()
    private val oval = RectF()
    private var cx = 0f
    private var cy = 0f
    private var outer = 0f
    private var inner = 0f
    private var ringText = ""
    private var lowerText = ""
    private var centreText = ""
    private var ringOffset = 0f
    private var lowerOffset = 0f
    private var ringSize = 0f
    private var lowerSize = 0f

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        prepare()
    }

    private fun prepare() {
        if (width == 0 || height == 0) return
        cx = width / 2f
        cy = height / 2f
        val stroke = (width * 0.026f).coerceAtLeast(1.2f * density)
        outer = min(cx, cy) - stroke * 1.5f
        inner = outer * 0.6f
        ringPaint.strokeWidth = stroke
        wavePaint.strokeWidth = stroke * 0.7f

        ringSize = outer * 0.2f
        lowerSize = outer * 0.17f
        val topRadius = outer * 0.74f
        oval.set(cx - topRadius, cy - topRadius, cx + topRadius, cy + topRadius)
        topArc.reset()
        topArc.addArc(oval, 180f, 180f)
        val bottomRadius = outer * 0.86f
        oval.set(cx - bottomRadius, cy - bottomRadius, cx + bottomRadius, cy + bottomRadius)
        bottomArc.reset()
        bottomArc.addArc(oval, 180f, -180f)

        val c = content
        ringText = fit(c?.ring.orEmpty().uppercase(), ringSize, topArc, ring = true)
        ringOffset = centreOffset(topArc, ringText, ringSize)
        lowerText = fit(c?.lower.orEmpty().uppercase(), lowerSize, bottomArc, ring = false)
        lowerOffset = centreOffset(bottomArc, lowerText, lowerSize)

        centrePaint.textSize = inner * 0.42f
        val centre = c?.centre.orEmpty()
        centreText = TextUtils.ellipsize(centre, centrePaint, inner * 1.7f, TextUtils.TruncateAt.END).toString()

        waves.reset()
        if (c?.blank == true) {
            // A cancellation: three soft waves across the middle, where a place name would be.
            val amplitude = inner * 0.12f
            for (line in -1..1) {
                val y = cy + line * inner * 0.32f
                waves.moveTo(cx - inner * 0.9f, y)
                var x = cx - inner * 0.9f
                val step = inner * 0.3f
                var up = true
                while (x < cx + inner * 0.9f) {
                    waves.quadTo(x + step / 2, y + if (up) -amplitude else amplitude, x + step, y)
                    x += step
                    up = !up
                }
            }
        }
    }

    /** Shrinks the text to fit 80 % of the arc, then shortens it with an ellipsis if it still does not fit. */
    private fun fit(text: String, size: Float, arc: Path, ring: Boolean): String {
        if (text.isEmpty()) return text
        val length = PathMeasure(arc, false).length * if (ring) 0.82f else 0.78f
        textPaint.textSize = size
        val width = textPaint.measureText(text)
        if (width <= length) return text
        val shrunk = size * (length / width)
        return if (shrunk >= size * 0.72f) {
            if (ring) ringSize = shrunk else lowerSize = shrunk
            text
        } else {
            val smallest = size * 0.72f
            if (ring) ringSize = smallest else lowerSize = smallest
            textPaint.textSize = smallest
            TextUtils.ellipsize(text, textPaint, length, TextUtils.TruncateAt.END).toString()
        }
    }

    private fun centreOffset(arc: Path, text: String, size: Float): Float {
        textPaint.textSize = size
        val length = PathMeasure(arc, false).length
        return ((length - textPaint.measureText(text)) / 2f).coerceAtLeast(0f)
    }

    override fun onDraw(canvas: Canvas) {
        if (content == null || outer <= 0f) return
        canvas.save()
        canvas.rotate(-7f, cx, cy)
        canvas.drawCircle(cx, cy, outer, ringPaint)
        canvas.drawCircle(cx, cy, inner, ringPaint)
        if (content?.blank == true) {
            canvas.drawPath(waves, wavePaint)
        } else {
            textPaint.textSize = ringSize
            canvas.drawTextOnPath(ringText, topArc, ringOffset, 0f, textPaint)
            if (centreText.isNotEmpty()) {
                val baseline = cy - (centrePaint.descent() + centrePaint.ascent()) / 2f
                canvas.drawText(centreText, cx, baseline, centrePaint)
            }
        }
        textPaint.textSize = lowerSize
        canvas.drawTextOnPath(lowerText, bottomArc, lowerOffset, 0f, textPaint)
        canvas.restore()
    }
}
