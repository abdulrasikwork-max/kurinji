package com.safesphere.app.feature.social.ui

import android.view.View
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

/**
 * Phase 17G-1: a multi-choice chip group filled in code (the sibling of [ChoiceChips]). [show] (re)builds the chips only when
 * the options change and sets the checked state without calling [onToggle], so rendering a state never feeds back into the
 * ViewModel. Chips that are not chosen are disabled while [limitReached], so the limit is visible, not a silent refusal.
 */
class MultiChips<T>(private val group: ChipGroup, private val onToggle: (T) -> Unit) {

    private var options: List<Pair<T, String>> = emptyList()

    fun show(newOptions: List<Pair<T, String>>, selected: Set<T>, limitReached: Boolean = false) {
        if (newOptions != options) {
            options = newOptions
            group.removeAllViews()
            newOptions.forEach { (value, label) ->
                group.addView(Chip(group.context).apply {
                    text = label
                    tag = value
                    isCheckable = true
                    id = View.generateViewId()
                    setOnClickListener {
                        @Suppress("UNCHECKED_CAST")
                        onToggle(tag as T)
                    }
                })
            }
        }
        for (i in 0 until group.childCount) {
            val chip = group.getChildAt(i) as Chip
            val chosen = chip.tag in selected
            chip.isChecked = chosen
            chip.isEnabled = chosen || !limitReached
        }
    }
}
