package com.safesphere.app.feature.rise.ui

import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import com.safesphere.app.R
import com.safesphere.app.core.ui.collectWhenStarted
import com.safesphere.app.databinding.FragmentRiseGoalEditorBinding
import com.safesphere.app.feature.rise.domain.RiseDomain

/**
 * A new goal: a domain (a code), optional words (≤ 80, on this phone only) and the person's own steps. The domain's
 * suggested steps fill the box until the person edits it; they are suggestions, never a ladder towards "less support".
 */
class RiseGoalEditorFragment : RiseScreenFragment<FragmentRiseGoalEditorBinding>(FragmentRiseGoalEditorBinding::inflate) {

    private var domain = RiseDomain.GO
    private var suggested = ""

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        domain = savedInstanceState?.getString(KEY_DOMAIN)?.let { name -> RiseDomain.entries.firstOrNull { it.name == name } }
            ?: RiseDomain.GO
        renderDomains()
        if (savedInstanceState == null) fillSuggestions()
        binding.saveButton.setOnClickListener {
            viewModel.addGoal(domain, binding.wordsInput.text?.toString().orEmpty(), binding.stepsInput.text?.toString().orEmpty())
        }
        collectReady { }
        collectWhenStarted(viewModel.events) { event ->
            if (event is RiseEvent.GoalSaved) {
                // The editor is left behind: Back from the new goal returns to the Rise tab.
                findNavController().navigate(R.id.riseGoalFragment, bundleOf(RiseGoalFragment.ARG_GOAL_ID to event.goalId),
                    NavOptions.Builder().setPopUpTo(R.id.riseGoalEditorFragment, true).build())
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_DOMAIN, domain.name)
    }

    private fun renderDomains() {
        binding.domainChips.fill(RiseDomain.entries, RiseTexts::domain, setOf(domain)) { item, checked ->
            if (checked && item != domain) {
                domain = item
                // Only replace suggestions the person has not changed.
                if (binding.stepsInput.text?.toString().orEmpty() == suggested) fillSuggestions()
            }
        }
    }

    private fun fillSuggestions() {
        suggested = RiseTexts.templates(domain).joinToString("\n") { getString(it) }
        binding.stepsInput.setText(suggested)
    }

    private companion object {
        const val KEY_DOMAIN = "domain"
    }
}
