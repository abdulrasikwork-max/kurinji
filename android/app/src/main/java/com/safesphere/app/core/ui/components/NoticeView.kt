package com.safesphere.app.core.ui.components

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.LinearLayout
import androidx.core.content.withStyledAttributes
import com.safesphere.app.R
import com.safesphere.app.databinding.ViewNoticeBinding
import com.google.android.material.R as MaterialR

/**
 * A rounded message box. `app:noticeType="info"` (teal) for preview-mode explanations,
 * `warning` (amber) for "this is not active" messages. XML: noticeType, noticeText.
 */
class NoticeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val binding = ViewNoticeBinding.inflate(LayoutInflater.from(context), this)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))

        context.withStyledAttributes(attrs, R.styleable.NoticeView) {
            binding.noticeText.text = getString(R.styleable.NoticeView_noticeText)
            applyType(isWarning = getInt(R.styleable.NoticeView_noticeType, 0) == 1)
        }
    }

    /** Changes the message at runtime (used for sign-in errors). */
    fun setMessage(text: CharSequence) {
        binding.noticeText.text = text
    }

    /** Switches between the info (teal) and warning (amber) look at runtime. */
    fun setWarning(isWarning: Boolean) = applyType(isWarning)

    private fun applyType(isWarning: Boolean) {
        val container = themeColor(if (isWarning) R.attr.colorCautionContainer else MaterialR.attr.colorPrimaryContainer)
        val content = themeColor(if (isWarning) R.attr.colorOnCautionContainer else MaterialR.attr.colorOnPrimaryContainer)

        background = roundedBackground(container, radiusDp = 14)
        binding.noticeText.setTextColor(content)
        binding.noticeIcon.setImageResource(if (isWarning) R.drawable.ic_warning else R.drawable.ic_info)
        binding.noticeIcon.imageTintList = ColorStateList.valueOf(content)
    }
}
