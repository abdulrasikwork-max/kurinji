package com.safesphere.app.core.ui.components

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.safesphere.app.R

/**
 * Phase 13F: editorial tabs ("Edition · Connections", "Want to go · Been"): words in a row, the chosen one underlined in
 * ink (the line and the word's colour, never colour alone). TalkBack hears "Tab, selected, 1 of 2".
 */
class TextTabsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private var onSelected: ((Int) -> Unit)? = null
    var selectedIndex: Int = -1
        private set

    init {
        orientation = HORIZONTAL
    }

    /**
     * Side by side when the words fit, one under the other when they do not (small screens, large text): a tab's word
     * is never broken in the middle (found on the emulator at 320 dp and 1.3x text).
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        var needed = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            child.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            needed += child.measuredWidth + ((child.layoutParams as? MarginLayoutParams)?.marginEnd ?: 0)
        }
        val wanted = if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED && needed > available) VERTICAL
        else HORIZONTAL
        if (orientation != wanted) orientation = wanted
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    fun setTabs(labels: List<String>, selected: Int, onSelected: (Int) -> Unit) {
        this.onSelected = onSelected
        if (childCount != labels.size || labels.indices.any { (getChildAt(it) as TextView).text != labels[it] }) {
            removeAllViews()
            labels.forEachIndexed { index, label ->
                val tab = TextView(context, null, 0, R.style.Widget_Kurinji_TextTab).apply {
                    text = label
                    setOnClickListener { select(index, fromUser = true) }
                }
                ViewCompat.setAccessibilityDelegate(tab, object : AccessibilityDelegateCompat() {
                    override fun onInitializeAccessibilityNodeInfo(host: android.view.View, info: AccessibilityNodeInfoCompat) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.roleDescription = context.getString(R.string.a11y_tab)
                        info.isSelected = host.isSelected
                        info.setCollectionItemInfo(AccessibilityNodeInfoCompat.CollectionItemInfoCompat.obtain(
                            0, 1, index, 1, false, host.isSelected))
                    }
                })
                addView(tab, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = resources.getDimensionPixelSize(R.dimen.space_lg)
                })
            }
        }
        select(selected, fromUser = false)
    }

    private fun select(index: Int, fromUser: Boolean) {
        if (index == selectedIndex && fromUser) return
        selectedIndex = index
        for (i in 0 until childCount) getChildAt(i).isSelected = i == index
        if (fromUser) onSelected?.invoke(index)
    }
}
