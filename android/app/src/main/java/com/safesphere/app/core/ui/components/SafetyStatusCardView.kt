package com.safesphere.app.core.ui.components

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.safesphere.app.R
import com.safesphere.app.databinding.ViewSafetyStatusCardBinding

/**
 * Colour of the card's label and icon. Never the only signal: every content also has its own words and icon.
 * Deliberately no green (it reads as "safe", which the app never claims: docs/PHASE8_DESIGN.md §5.6) and no red
 * (emergency UI only: during an SOS, Home hides this card and the red banner is the one emergency element).
 */
enum class StatusTone(@ColorRes val colorRes: Int) {
    /** Neutral teal: a plain fact. */
    CALM(R.color.hero_accent),

    /** Amber, always shown with a warning icon: something is waiting for the person's answer. */
    CAUTION(R.color.hero_caution)
}

/** What the card shows. Words and an icon only: the screen decides which content applies. */
data class StatusCardContent(
    @StringRes val labelRes: Int,
    @StringRes val headlineRes: Int,
    @StringRes val detailRes: Int,
    @DrawableRes val iconRes: Int,
    val tone: StatusTone
)

/**
 * The navy status card on Home. It is UI only: it never calculates anything, it just displays the
 * [StatusCardContent] it is given, with a short cross-fade when the content changes.
 */
class SafetyStatusCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val binding = ViewSafetyStatusCardBinding.inflate(LayoutInflater.from(context), this)
    private var currentContent: StatusCardContent? = null

    init {
        // The card is read by screen readers as one sentence (see cd_status_card).
        isFocusable = true
        binding.statusContent.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    fun setContent(content: StatusCardContent, animate: Boolean = true) {
        if (content == currentContent) return
        val shouldAnimate = animate && currentContent != null && ValueAnimator.areAnimatorsEnabled()
        currentContent = content

        if (!shouldAnimate) {
            render(content)
            return
        }
        binding.statusContent.animate().alpha(0f).setDuration(120).withEndAction {
            render(content)
            binding.statusContent.animate().alpha(1f).setDuration(180).start()
        }.start()
    }

    private fun render(content: StatusCardContent) {
        val toneColor = ContextCompat.getColor(context, content.tone.colorRes)
        val label = context.getString(content.labelRes)
        val headline = context.getString(content.headlineRes)
        val detail = context.getString(content.detailRes)

        binding.statusLabel.text = label
        binding.statusLabel.setTextColor(toneColor)
        binding.statusHeadline.text = headline
        binding.statusDetail.text = detail
        binding.statusIcon.setImageResource(content.iconRes)
        binding.statusIcon.imageTintList = ColorStateList.valueOf(toneColor)
        binding.statusIconContainer.background =
            roundedBackground(ColorUtils.setAlphaComponent(toneColor, 46))

        contentDescription = context.getString(R.string.cd_status_card, label, headline, detail)
    }
}
