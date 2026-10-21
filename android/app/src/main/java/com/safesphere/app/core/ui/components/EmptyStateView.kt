package com.safesphere.app.core.ui.components

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.LinearLayout
import androidx.core.content.withStyledAttributes
import com.safesphere.app.R
import com.safesphere.app.databinding.ViewEmptyStateBinding

/** Icon + title + message shown when a list has nothing in it. XML: emptyIcon, emptyTitle, emptyMessage. */
class EmptyStateView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val binding = ViewEmptyStateBinding.inflate(LayoutInflater.from(context), this)

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(24), dp(24), dp(24), dp(24))

        context.withStyledAttributes(attrs, R.styleable.EmptyStateView) {
            getResourceId(R.styleable.EmptyStateView_emptyIcon, 0)
                .takeIf { it != 0 }
                ?.let { binding.emptyIcon.setImageResource(it) }
            binding.emptyTitle.text = getString(R.styleable.EmptyStateView_emptyTitle)
            binding.emptyMessage.text = getString(R.styleable.EmptyStateView_emptyMessage)
        }
    }
}
