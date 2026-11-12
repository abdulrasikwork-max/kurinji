package com.safesphere.app.feature.social.ui

import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.safesphere.app.R
import com.safesphere.app.appContainer
import com.safesphere.app.core.ui.ViewBindingFragment
import com.safesphere.app.core.ui.collectWhenStarted
import com.safesphere.app.core.ui.showSessionExpiredDialog
import com.safesphere.app.core.ui.toMessage
import com.safesphere.app.databinding.FragmentExploreBinding
import com.safesphere.app.feature.social.domain.Cities
import com.safesphere.app.feature.social.domain.Daypart
import com.safesphere.app.feature.social.domain.ExploreFacets
import com.safesphere.app.feature.social.domain.Interest

/**
 * Phase 17G-1: Explore. Type what you are looking for: the phone understands the words with its own word list (nothing
 * typed leaves the phone) and shows what it understood as chips you can change; Search sends ONLY the chips. Results are
 * places; a tap opens the place's page. No history: leaving the screen forgets everything.
 */
class ExploreFragment : ViewBindingFragment<FragmentExploreBinding>(FragmentExploreBinding::inflate) {

    private val viewModel by viewModels<ExploreViewModel> {
        ExploreViewModel.factory(appContainer, Cities.listCity(arguments?.getString(ExploreViewModel.ARG_CITY) ?: appContainer.feedFreshness.cityCode))
    }

    private lateinit var categoryChips: MultiChips<Interest>
    private lateinit var facetChips: MultiChips<String>
    private lateinit var daypartChips: MultiChips<Daypart>
    private val adapter = ExploreHitAdapter { hit ->
        findNavController().navigate(R.id.postCollectionFragment,
            bundleOf(PostCollectionViewModel.ARG_PLACE_ID to hit.placeId))
    }

    /** True while the screen writes the typed text itself, so rendering never feeds back into the ViewModel. */
    private var rendering = false

    /** Set by a search the person started; cleared when its answer has been brought into view. */
    private var showAnswerWhenReady = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.exploreResults.adapter = adapter
        // Section titles are headings for screen readers (set here: the XML attribute needs API 28, the app supports 26).
        listOf(binding.exploreCategoriesTitle, binding.exploreFacetsTitle, binding.exploreDaypartTitle,
            binding.exploreResultsHeader, binding.exploreEmptyTitle).forEach { ViewCompat.setAccessibilityHeading(it, true) }
        categoryChips = MultiChips(binding.exploreCategoryGroup) { viewModel.onToggleCategory(it); render(viewModel.uiState.value) }
        facetChips = MultiChips(binding.exploreFacetGroup) { viewModel.onToggleFacet(it); render(viewModel.uiState.value) }
        daypartChips = MultiChips(binding.exploreDaypartGroup) { viewModel.onDaypart(it); render(viewModel.uiState.value) }

        binding.exploreInput.doAfterTextChanged { if (!rendering) viewModel.onText(it?.toString().orEmpty()) }
        binding.exploreInput.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) startSearch()
            action == EditorInfo.IME_ACTION_SEARCH
        }
        binding.exploreCityButton.setOnClickListener {
            CityPicker.show(this, viewModel.uiState.value.city, allowOther = false) { viewModel.onCity(it) }
        }
        binding.exploreSearchButton.setOnClickListener { startSearch() }
        binding.exploreClearButton.setOnClickListener { viewModel.onClear() }
        // Phase 17G-3: turning smart understanding ON asks the question first; turning it OFF needs no question.
        binding.exploreSmartSwitch.setOnCheckedChangeListener { button, on ->
            if (!button.isPressed) return@setOnCheckedChangeListener
            if (on) {
                button.isChecked = false   // stays off until the person confirms
                confirmSmart()
            } else {
                if (viewModel.uiState.value.smartOn) viewModel.onSmartOff()   // not when we just reset it ourselves
            }
        }
        collectWhenStarted(viewModel.uiState, ::render)
    }

    /**
     * Real-device sprint (C5): the keyboard's search key left the keyboard open, so the answer appeared out of sight below it.
     * Searching now closes the keyboard and, once the answer (or its failure) is drawn, brings it into view.
     */
    private fun startSearch() {
        // Always close the keyboard: when nothing was understood the "We did not use ..." line must not stay hidden behind it.
        binding.exploreInput.clearFocus()
        WindowCompat.getInsetsController(requireActivity().window, binding.exploreInput).hide(WindowInsetsCompat.Type.ime())
        if (!viewModel.uiState.value.canSearch) return
        showAnswerWhenReady = true
        viewModel.search()
    }

    /** Scrolls to whichever of results / empty message / failure is on screen; the end of a search, never on later redraws. */
    private fun showAnswerIfWaiting(state: ExploreUiState) {
        if (!showAnswerWhenReady) return
        val target = when (state.phase) {
            ExplorePhase.RESULTS ->
                if (state.result?.hits.orEmpty().isEmpty()) binding.exploreEmptyTitle else binding.exploreResultsHeader
            ExplorePhase.FAILED -> binding.exploreNotice
            else -> return   // still loading (or the search ended without one): keep waiting
        }
        showAnswerWhenReady = false
        binding.root.post { if (view != null) binding.root.smoothScrollTo(0, target.top) }
    }

    /** The one honest question before any typed word can leave the phone for smart understanding. */
    private fun confirmSmart() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.explore_smart_dialog_title)
            .setMessage(R.string.explore_smart_dialog_message)
            .setPositiveButton(R.string.explore_smart_dialog_on) { _, _ -> viewModel.onSmartConfirmed() }
            .setNegativeButton(R.string.explore_smart_dialog_cancel, null)
            .show()
    }

    private fun render(state: ExploreUiState) {
        if (view == null) return
        // Phase 17G-3: the switch exists only when the server offers it; the helper tells the truth about where the words go.
        binding.exploreSmartSwitch.isVisible = state.smartAvailable
        binding.exploreSmartSummary.isVisible = state.smartAvailable
        if (binding.exploreSmartSwitch.isChecked != state.smartOn) binding.exploreSmartSwitch.isChecked = state.smartOn
        binding.exploreHelper.setText(if (state.smartOn && state.smartAvailable) R.string.explore_helper_smart else R.string.explore_helper)
        val note = when (state.understoodBy) {
            UnderstoodBy.SMART -> R.string.explore_understood_smart
            UnderstoodBy.FALLBACK -> R.string.explore_understood_standard
            UnderstoodBy.PHONE -> null
        }
        binding.exploreSmartNote.isVisible = note != null
        if (note != null) binding.exploreSmartNote.setText(note)
        if (binding.exploreInput.text?.toString() != state.text) {
            rendering = true
            binding.exploreInput.setText(state.text)
            binding.exploreInput.setSelection(state.text.length)
            rendering = false
        }
        binding.exploreCityButton.text = state.city?.let { getString(R.string.explore_city_label, it.name) }
            ?: getString(R.string.explore_city_pick)

        categoryChips.show(Interest.entries.map { it to getString(SocialLabels.of(it)) }, state.categories.toSet(),
            state.atCategoryLimit)
        facetChips.show(ExploreFacets.all.map { it.code to it.english }, state.facets.toSet(), state.atFacetLimit)
        daypartChips.show(Daypart.entries.map { it to getString(ExperienceLabels.of(it)) }, setOfNotNull(state.daypart))

        binding.exploreUnused.isVisible = state.unused.isNotEmpty()
        binding.exploreUnused.text = getString(R.string.explore_unused, state.unused.joinToString(", "))

        val asked = buildList {
            state.categories.forEach { add(getString(SocialLabels.of(it))) }
            state.facets.mapNotNull { ExploreFacets.find(it)?.english }.forEach(::add)
            state.daypart?.let { add(getString(ExperienceLabels.of(it))) }
        }
        binding.exploreSummary.text = if (asked.isEmpty()) getString(R.string.explore_nothing_asked)
            else getString(R.string.explore_searching_for, asked.joinToString(" · "))
        binding.exploreSearchButton.isEnabled = state.canSearch

        binding.exploreProgress.isVisible = state.phase == ExplorePhase.LOADING
        val failure = state.failure
        binding.exploreNotice.isVisible = state.phase == ExplorePhase.FAILED && failure != null
        if (failure != null) binding.exploreNotice.setMessage(failure.toMessage(requireContext()))

        val hits = state.result?.hits.orEmpty()
        val answered = state.phase == ExplorePhase.RESULTS
        binding.exploreResultsHeader.isVisible = answered && hits.isNotEmpty()
        binding.exploreResults.isVisible = answered && hits.isNotEmpty()
        binding.exploreEnd.isVisible = answered && hits.isNotEmpty()
        binding.exploreEmptyTitle.isVisible = answered && hits.isEmpty()
        binding.exploreEmptyMessage.isVisible = answered && hits.isEmpty()
        adapter.submitList(hits)
        showAnswerIfWaiting(state)

        if (state.sessionExpired) {
            viewModel.onSessionExpiredHandled()
            showSessionExpiredDialog()
        }
    }
}
