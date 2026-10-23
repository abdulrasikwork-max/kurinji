package com.safesphere.app.feature.social.ui

import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

/**
 * Phase 13B: a single-choice chip group filled in code. [show] (re)builds the chips only when the options change and
 * sets the checked chip without calling [onPick] again, so rendering a state never feeds back into the ViewModel.
 */
class ChoiceChips<T>(private val group: ChipGroup, private val onPick: (T?) -> Unit) {

    private var options: List<Pair<T, String>> = emptyList()
    private var rendering = false

    init {
        group.setOnCheckedStateChangeListener { _, checkedIds ->
            if (rendering) return@setOnCheckedStateChangeListener
            val chip = checkedIds.firstOrNull()?.let { group.findViewById<Chip>(it) }
            @Suppress("UNCHECKED_CAST")
            onPick(chip?.tag as T?)
        }
    }

    fun show(newOptions: List<Pair<T, String>>, selected: T?, enabled: Boolean = true) {
        rendering = true
        try {
            if (newOptions != options) {
                options = newOptions
                group.removeAllViews()
                newOptions.forEach { (value, label) ->
                    group.addView(Chip(group.context).apply {
                        text = label
                        tag = value
                        isCheckable = true
                        id = android.view.View.generateViewId()
                    })
                }
            }
            for (i in 0 until group.childCount) {
                val chip = group.getChildAt(i) as Chip
                chip.isChecked = chip.tag == selected
                chip.isEnabled = enabled
            }
        } finally {
            rendering = false
        }
    }
}
