package com.safesphere.app.core.ui.components

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.LinearLayout
import androidx.core.content.withStyledAttributes
import androidx.core.view.ViewCompat
import com.safesphere.app.R
import com.safesphere.app.databinding.ViewSectionHeaderBinding

/** Section title with an optional status badge on the right. XML: sectionTitle, badgeStatus. */
class SectionHeaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val binding = ViewSectionHeaderBinding.inflate(LayoutInflater.from(context), this)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        ViewCompat.setAccessibilityHeading(binding.sectionTitle, true)

        context.withStyledAttributes(attrs, R.styleable.SectionHeaderView) {
            binding.sectionTitle.text = getString(R.styleable.SectionHeaderView_sectionTitle)
            binding.sectionBadge.setStatus(
                BadgeStatus.fromAttr(getInt(R.styleable.SectionHeaderView_badgeStatus, -1))
            )
        }
    }
}
