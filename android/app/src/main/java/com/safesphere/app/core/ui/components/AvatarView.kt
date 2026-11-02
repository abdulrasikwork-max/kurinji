package com.safesphere.app.core.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import coil.ImageLoader
import coil.dispose
import coil.load
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.shape.ShapeAppearanceModel
import com.safesphere.app.R

/**
 * Phase 16: a person's initial in a strong coloured circle; Phase 17E: their profile photo when they have one. The
 * colour comes from the handle, so the same person always looks the same, and white on every avatar colour reaches
 * 4.5:1 (ContrastTest). The photo comes through the ONE Coil loader (memory cache only, JWT attached) and is cancelled
 * when the view is reused, so a recycled row never shows someone else's face. Decorative for TalkBack: the name next to
 * it is read instead.
 */
class AvatarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ShapeableImageView(context, attrs) {

    private val circle = Paint(Paint.ANTI_ALIAS_FLAG)
    private val letter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.avatar_on)
        textAlign = Paint.Align.CENTER
        typeface = ResourcesCompat.getFont(context, R.font.kurinji_display)
    }
    private var initial = ""
    private var shownUrl: String? = null

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        scaleType = ScaleType.CENTER_CROP
        shapeAppearanceModel = ShapeAppearanceModel.builder().setAllCornerSizes(
            com.google.android.material.shape.RelativeCornerSize(0.5f)).build()
        set(null, null)
    }

    /** Shows [name]'s first letter in the colour of [key] (the handle); both null = a neutral circle. No photo. */
    fun set(name: String?, key: String?) {
        if (shownUrl != null) {
            dispose()
            shownUrl = null
        }
        setImageDrawable(null)
        initial = initialOf(name)
        circle.color = ContextCompat.getColor(context, COLOURS[colourIndex(key)])
        invalidate()
    }

    /**
     * The initial at once, then the photo at [photoUrl] when there is one (null = none). A failed load leaves the
     * initial: a photo is never required to recognise someone.
     */
    fun set(name: String?, key: String?, photoUrl: String?, loader: ImageLoader) {
        if (photoUrl != null && photoUrl == shownUrl && drawable != null) return
        set(name, key)
        if (photoUrl == null) return
        shownUrl = photoUrl
        load(photoUrl, loader) {
            crossfade(true)
            listener(onError = { _, _ -> setImageDrawable(null) })
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (drawable != null) {
            super.onDraw(canvas)
            return
        }
        val r = minOf(width, height) / 2f
        canvas.drawCircle(width / 2f, height / 2f, r, circle)
        if (initial.isEmpty()) return
        letter.textSize = r * 1.05f
        val y = height / 2f - (letter.descent() + letter.ascent()) / 2f
        canvas.drawText(initial, width / 2f, y, letter)
    }

    companion object {
        private val COLOURS = intArrayOf(R.color.avatar_1, R.color.avatar_2, R.color.avatar_3, R.color.avatar_4,
            R.color.avatar_5, R.color.avatar_6)

        /** The first letter (a whole code point, so Tamil and emoji-free names work), upper-cased. */
        fun initialOf(name: String?): String {
            val text = name?.trim().orEmpty()
            if (text.isEmpty()) return ""
            val end = text.offsetByCodePoints(0, 1)
            return text.substring(0, end).uppercase()
        }

        /** A stable colour per handle; a missing handle gets the first colour. */
        fun colourIndex(key: String?): Int = if (key.isNullOrEmpty()) 0 else Math.floorMod(key.hashCode(), 6)
    }
}
