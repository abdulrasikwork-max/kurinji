package com.safesphere.app.core.ui.components

import android.graphics.drawable.GradientDrawable
import android.view.View
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import com.google.android.material.color.MaterialColors
import kotlin.math.roundToInt

/** Reads a colour from the current theme, so light and dark mode both work without extra code. */
@ColorInt
fun View.themeColor(@AttrRes attr: Int): Int = MaterialColors.getColor(this, attr)

fun View.dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

/** A solid rounded background. Used by badges, notices and icon circles drawn in code. */
fun View.roundedBackground(@ColorInt color: Int, radiusDp: Int = 999): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusDp * resources.displayMetrics.density
        setColor(color)
    }
