package com.safesphere.app.core.ui.components

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import androidx.annotation.StringRes
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.core.content.withStyledAttributes
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.isVisible
import com.safesphere.app.R

/**
 * A small pill: icon + text + colour for one [BadgeStatus].
 * Use `app:badgeStatus="preview"` in XML, or call [setStatus] from code.
 */
class StatusBadgeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

    init {
        setTextAppearance(R.style.TextAppearance_SafeSphere_StatusLabel)
        gravity = Gravity.CENTER_VERTICAL
        compoundDrawablePadding = dp(4)
        setPadding(dp(10), dp(4), dp(10), dp(4))

        context.withStyledAttributes(attrs, R.styleable.StatusBadgeView) {
            setStatus(BadgeStatus.fromAttr(getInt(R.styleable.StatusBadgeView_badgeStatus, -1)))
        }
    }

    /** Shows [status], optionally with a shorter [labelRes]. A null status hides the badge. */
    fun setStatus(status: BadgeStatus?, @StringRes labelRes: Int? = null) {
        setStatus(status, labelRes?.let { context.getString(it) })
    }

    /** Shows [status] with a custom [label] (for example "Priority 2"). A null status hides the badge. */
    fun setStatus(status: BadgeStatus?, label: CharSequence?) {
        if (status == null) {
            isVisible = false
            return
        }
        isVisible = true

        val contentColor = themeColor(status.contentAttr)
        text = label ?: context.getString(status.labelRes)
        setTextColor(contentColor)
        background = roundedBackground(themeColor(status.containerAttr))

        val iconSize = resources.getDimensionPixelSize(R.dimen.icon_size_small)
        val icon = status.iconRes?.let { ContextCompat.getDrawable(context, it)?.mutate() }
        icon?.let {
            DrawableCompat.setTint(it, contentColor)
            it.setBounds(0, 0, iconSize, iconSize)
        }
        setCompoundDrawablesRelative(icon, null, null, null)
    }
}
