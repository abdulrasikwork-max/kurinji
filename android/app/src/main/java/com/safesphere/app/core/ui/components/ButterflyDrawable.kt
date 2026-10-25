package com.safesphere.app.core.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import androidx.annotation.ColorInt
import androidx.core.graphics.PathParser
import com.google.android.material.color.MaterialColors
import com.safesphere.app.R

/**
 * Phase 13F: the butterfly, drawn (never an emoji, which looks different on every phone). The Tamil Yeoman, Tamil
 * Nadu's state butterfly: orange wings with dark edges. [flap] folds the wings (0 = open, 1 = almost closed) so the
 * flight and the emergence can beat its wings; it is just a number, so a test or a reduced-motion phone can ignore it.
 */
class ButterflyDrawable(
    @ColorInt private val wing: Int,
    @ColorInt private val edge: Int,
    private val intrinsicPx: Int
) : Drawable() {

    /** 0 = wings open, 1 = wings folded to a quarter of their width. */
    var flap: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidateSelf()
        }

    private val wingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = wing; style = Paint.Style.FILL }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = edge
        style = Paint.Style.STROKE
        strokeWidth = 2f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = edge; style = Paint.Style.FILL }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val scale = minOf(b.width() / VIEW_W, b.height() / VIEW_H)
        canvas.save()
        canvas.translate(b.left + (b.width() - VIEW_W * scale) / 2f, b.top + (b.height() - VIEW_H * scale) / 2f)
        canvas.scale(scale, scale)
        val fold = 1f - 0.75f * flap
        for (wingPath in listOf(LEFT, RIGHT)) {
            canvas.save()
            canvas.scale(fold, 1f, BODY_X, 0f)
            canvas.drawPath(wingPath, wingPaint)
            canvas.drawPath(wingPath, edgePaint)
            canvas.restore()
        }
        canvas.drawPath(BODY, bodyPaint)
        canvas.drawPath(ANTENNAE, edgePaint)
        canvas.restore()
    }

    override fun getIntrinsicWidth(): Int = intrinsicPx

    override fun getIntrinsicHeight(): Int = (intrinsicPx * VIEW_H / VIEW_W).toInt()

    override fun setAlpha(alpha: Int) {
        wingPaint.alpha = alpha
        edgePaint.alpha = alpha
        bodyPaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        wingPaint.colorFilter = colorFilter
        edgePaint.colorFilter = colorFilter
        bodyPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java", ReplaceWith("PixelFormat.TRANSLUCENT", "android.graphics.PixelFormat"))
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        private const val VIEW_W = 48f
        private const val VIEW_H = 40f
        private const val BODY_X = 24f

        // Paths in a 48 x 40 box (the same drawing as the launcher icon and the design page).
        private val LEFT: Path = PathParser.createPathFromPathData(
            "M23,18C16,6 4,4 3,12C2,19 12,22 23,21Z M23,22C14,22 6,27 9,34C12,39 20,33 23,25Z")
        private val RIGHT: Path = PathParser.createPathFromPathData(
            "M25,18C32,6 44,4 45,12C46,19 36,22 25,21Z M25,22C34,22 42,27 39,34C36,39 28,33 25,25Z")
        private val BODY: Path = PathParser.createPathFromPathData("M22.2,22a1.8,9 0,1 0,3.6 0a1.8,9 0,1 0,-3.6 0Z")
        private val ANTENNAE: Path = PathParser.createPathFromPathData("M23.4,13.5L19.5,5M24.6,13.5L28.5,5")

        /** The intent butterfly in the theme's colours ("Want to go"), [sizeDp] wide. */
        fun intent(context: Context, sizeDp: Int = 28): ButterflyDrawable {
            val probe = android.view.View(context)
            return ButterflyDrawable(
                wing = MaterialColors.getColor(probe, R.attr.colorIntent),
                edge = MaterialColors.getColor(probe, com.google.android.material.R.attr.colorOnSurface),
                intrinsicPx = (sizeDp * context.resources.displayMetrics.density).toInt()
            )
        }
    }
}
