package com.safesphere.app.core.ui.components

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView

/**
 * Phase 13F: a photo whose height follows its width and the photo's own shape (known from the post before it loads),
 * so cards never jump while images arrive. The ratio is kept between 16:9 and 4:5 so one tall photo cannot fill a screen.
 */
class AspectRatioImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    /** Height divided by width. */
    var ratio: Float = 0.75f
        set(value) {
            val clamped = value.coerceIn(MIN_RATIO, MAX_RATIO)
            if (clamped == field) return
            field = clamped
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, (width * ratio).toInt())
    }

    fun setPhotoSize(photoWidth: Int, photoHeight: Int) {
        if (photoWidth > 0 && photoHeight > 0) ratio = photoHeight.toFloat() / photoWidth
    }

    private companion object {
        const val MIN_RATIO = 9f / 16f
        const val MAX_RATIO = 5f / 4f
    }
}
