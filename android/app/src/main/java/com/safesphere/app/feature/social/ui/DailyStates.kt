package com.safesphere.app.feature.social.ui

import android.view.LayoutInflater
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.safesphere.app.R
import com.safesphere.app.databinding.SheetDailyStateBinding
import com.safesphere.app.feature.social.domain.DailyState

/**
 * Phase 17E (D-7): today's state, as words and a drawn icon (no emoji: KurinjiTextsTest). The list is a closed set of
 * moods and intents; none says where someone is or that they are free right now.
 */
object DailyStates {

    fun label(state: DailyState): Int = when (state) {
        DailyState.CURIOUS -> R.string.daily_state_curious
        DailyState.PLANNING -> R.string.daily_state_planning
        DailyState.INSPIRED -> R.string.daily_state_inspired
        DailyState.LEARNING -> R.string.daily_state_learning
        DailyState.CELEBRATING -> R.string.daily_state_celebrating
        DailyState.SLOW_DAY -> R.string.daily_state_slow_day
        DailyState.BUSY_DAY -> R.string.daily_state_busy_day
        DailyState.QUIET_DAY -> R.string.daily_state_quiet_day
    }

    fun icon(state: DailyState): Int = when (state) {
        DailyState.CURIOUS -> R.drawable.ic_state_curious
        DailyState.PLANNING -> R.drawable.ic_state_planning
        DailyState.INSPIRED -> R.drawable.ic_state_inspired
        DailyState.LEARNING -> R.drawable.ic_state_learning
        DailyState.CELEBRATING -> R.drawable.ic_state_celebrating
        DailyState.SLOW_DAY -> R.drawable.ic_state_slow_day
        DailyState.BUSY_DAY -> R.drawable.ic_state_busy_day
        DailyState.QUIET_DAY -> R.drawable.ic_state_quiet_day
    }

    /** The sheet: every state, then "Clear" when one is set. [onPick] gets null for "Clear". */
    fun pick(fragment: Fragment, current: DailyState?, onPick: (DailyState?) -> Unit) {
        val context = fragment.requireContext()
        val dialog = BottomSheetDialog(context)
        val sheet = SheetDailyStateBinding.inflate(LayoutInflater.from(context))
        fun row(text: String, icon: Int, chosen: Boolean, action: () -> Unit) {
            val view = TextView(context, null, 0, R.style.Widget_Kurinji_SheetRow).apply {
                this.text = text
                setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, if (chosen) R.drawable.ic_check else 0, 0)
                contentDescription = if (chosen) context.getString(R.string.city_picker_chosen).let { "$text, $it" } else text
                setOnClickListener {
                    dialog.dismiss()
                    action()
                }
            }
            androidx.core.widget.TextViewCompat.setCompoundDrawableTintList(view,
                android.content.res.ColorStateList.valueOf(com.google.android.material.color.MaterialColors.getColor(
                    view, com.google.android.material.R.attr.colorOnSurface)))
            sheet.options.addView(view, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        DailyState.entries.forEach { state ->
            row(context.getString(label(state)), icon(state), state == current) { onPick(state) }
        }
        if (current != null) row(context.getString(R.string.daily_state_clear), R.drawable.ic_close, false) { onPick(null) }
        dialog.setContentView(sheet.root)
        dialog.show()
    }
}
