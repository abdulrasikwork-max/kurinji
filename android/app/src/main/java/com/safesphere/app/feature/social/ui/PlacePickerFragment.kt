package com.safesphere.app.feature.social.ui

import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.safesphere.app.R
import com.safesphere.app.appContainer
import com.safesphere.app.core.location.LocationAccess
import com.safesphere.app.core.location.LocationNeed
import com.safesphere.app.core.location.LocationNeeds
import com.safesphere.app.core.location.LocationPermissionStatus
import com.safesphere.app.core.ui.LocationAsk
import com.safesphere.app.core.ui.ViewBindingFragment
import com.safesphere.app.core.ui.collectWhenStarted
import com.safesphere.app.core.ui.showSessionExpiredDialog
import com.safesphere.app.core.ui.toMessage
import com.safesphere.app.databinding.FragmentPlacePickerBinding
import com.safesphere.app.feature.social.domain.Interest
import com.safesphere.app.feature.social.domain.Place

/**
 * Phase 13B: where it was. Phase 17D: three ways to choose: "Use where I am" (location asked here, in context, and read
 * only when tapped), "Search" (place names; nothing but the words is sent) and "Places you used"; plus "Add this
 * place" at the current ~150 m block, "Only the city" and "No place". The choice goes back to the composer.
 * Each location problem has one button that fixes it (allow, open settings, turn location on, use precise location,
 * try again), the permission is asked through the shared [LocationAsk], and coming back from settings retries.
 */
class PlacePickerFragment : ViewBindingFragment<FragmentPlacePickerBinding>(FragmentPlacePickerBinding::inflate) {

    private val viewModel by viewModels<PlacePickerViewModel> { PlacePickerViewModel.factory(appContainer) }

    private val locationAsk = LocationAsk(this) { status ->
        if (status == LocationPermissionStatus.GRANTED_PRECISE || status == LocationPermissionStatus.GRANTED_APPROXIMATE) {
            viewModel.onPermissionResult()
        } else {
            render(viewModel.uiState.value)
        }
    }

    private val nearbyAdapter = PersonRowAdapter()
    private val recentAdapter = PersonRowAdapter()
    private val searchAdapter = PersonRowAdapter()
    private lateinit var categoryChips: ChoiceChips<Interest>
    private var category: Interest? = null
    /** Sent to a settings page: try again when the person comes back. */
    private var retryOnReturn = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.nearbyList.adapter = nearbyAdapter
        binding.recentList.adapter = recentAdapter
        binding.searchList.adapter = searchAdapter
        categoryChips = ChoiceChips(binding.placeCategoryGroup) { category = it }
        binding.nearMeButton.setOnClickListener {
            if (appContainer.screenLocationProvider.access() == LocationAccess.NONE && !locationAsk.permanentlyDenied) {
                locationAsk.ask()
            } else {
                viewModel.onNearMe()
            }
        }
        binding.searchInput.doAfterTextChanged {
            val text = it?.toString().orEmpty()
            if (text != viewModel.uiState.value.query) viewModel.onQuery(text)
        }
        binding.addPlaceButton.setOnClickListener {
            val name = binding.placeNameInput.text?.toString().orEmpty().trim()
            val chosen = category
            binding.placeNameLayout.error = if (name.codePointCount(0, name.length) < 2)
                getString(R.string.social_add_place_name_short) else null
            if (binding.placeNameLayout.error == null && chosen != null) viewModel.onAddPlace(name, chosen)
        }
        binding.cityOnlyButton.setOnClickListener { finish(bundleOf(KEY_CHOICE to CHOICE_CITY)) }
        binding.noPlaceButton.setOnClickListener { finish(bundleOf(KEY_CHOICE to CHOICE_NONE)) }
        collectWhenStarted(viewModel.uiState, ::render)
    }

    override fun onResume() {
        super.onResume()
        if (retryOnReturn) {
            retryOnReturn = false
            viewModel.onNearMe()
        }
    }

    override fun onStop() {
        super.onStop()
        viewModel.onScreenHidden() // location only while this screen is open
    }

    private fun render(state: PlacePickerUiState) {
        binding.progress.isVisible = state.isBusy
        binding.preciseBlock.isVisible = state.findingPrecise
        binding.nearMeButton.isEnabled = !state.isBusy
        recentAdapter.submitList(state.recent.map(::row))
        binding.recentBlock.isVisible = state.recent.isNotEmpty()
        val nearby = state.nearby
        binding.nearbyBlock.isVisible = nearby != null
        nearbyAdapter.submitList(nearby.orEmpty().map(::row))
        binding.nearbyEmpty.isVisible = nearby != null && nearby.isEmpty()
        binding.addBlock.isVisible = state.hereCell != null && !state.hereIsPrivate
        binding.addPlaceButton.isEnabled = !state.isBusy && state.canAddHere
        categoryChips.show(Interest.entries.map { it to getString(SocialLabels.of(it)) }, category)

        renderSearch(state)

        val notice = state.notice
        binding.pickerNotice.isVisible = notice != null
        renderNotice(notice)

        val failure = state.failure
        binding.errorNotice.isVisible = failure != null
        if (failure != null) binding.errorNotice.setMessage(failure.toMessage(requireContext()))

        state.chosen?.let { place ->
            viewModel.onChosenHandled()
            finish(bundleOf(KEY_CHOICE to CHOICE_PLACE, KEY_ID to place.publicId, KEY_NAME to place.name,
                KEY_CATEGORY to place.category.name, KEY_CELL to place.cell, KEY_AREA to place.areaCell))
            return
        }
        if (state.sessionExpired) {
            viewModel.onSessionExpiredHandled()
            showSessionExpiredDialog()
        }
    }

    /** Results, or one calm line saying why there are none; a failed search never hides the other two ways. */
    private fun renderSearch(state: PlacePickerUiState) {
        binding.searchProgress.isVisible = state.searching
        val results = state.searchResults
        searchAdapter.submitList(results.orEmpty().map(::row))
        binding.searchList.isVisible = !results.isNullOrEmpty()
        val message = when {
            state.searchProblem == SearchProblem.TOO_MANY -> R.string.social_search_too_many
            state.searchProblem == SearchProblem.FAILED -> R.string.social_search_failed
            results != null && results.isEmpty() && !state.searching -> R.string.social_search_none
            else -> null
        }
        binding.searchMessage.isVisible = message != null
        if (message != null) binding.searchMessage.setText(message)
    }

    /** What would help now, from the phone's facts and what the permission dialog answered (pure: [LocationNeeds]). */
    private fun need(wantsPrecise: Boolean): LocationNeed = LocationNeeds.of(
        access = appContainer.screenLocationProvider.access(),
        locationEnabled = appContainer.screenLocationProvider.isLocationEnabled(),
        wantsPrecise = wantsPrecise,
        permanentlyDenied = locationAsk.permanentlyDenied,
        preciseDeclined = locationAsk.preciseDeclined
    )

    /** The words of the notice and the one button that fixes it (none for a private place). */
    private fun renderNotice(notice: PickerNotice?) {
        val button = binding.allowLocationButton
        val (message, action) = when (notice) {
            PickerNotice.NEEDS_PERMISSION -> if (locationAsk.permanentlyDenied)
                R.string.social_location_denied_settings to Action(R.string.location_action_settings) {
                    retryOnReturn = true
                    locationAsk.act(LocationNeed.OPEN_APP_SETTINGS)
                }
            else R.string.social_location_needed to Action(R.string.action_allow_location) { locationAsk.act(LocationNeed.ALLOW) }
            PickerNotice.LOCATION_OFF -> R.string.social_location_off to Action(R.string.location_action_turn_on) {
                retryOnReturn = true
                locationAsk.act(LocationNeed.TURN_ON)
            }
            PickerNotice.LOCATION_UNAVAILABLE ->
                R.string.social_location_unavailable to Action(R.string.location_action_retry) { viewModel.onNearMe() }
            // Approximate only: one tap to Android's upgrade dialog; once it came back approximate, the settings toggle.
            PickerNotice.NEEDS_PRECISE -> R.string.social_add_place_needs_precise to when (need(wantsPrecise = true)) {
                LocationNeed.UPGRADE_PRECISE_IN_SETTINGS -> Action(R.string.location_action_settings) {
                    retryOnReturn = true
                    locationAsk.act(LocationNeed.UPGRADE_PRECISE_IN_SETTINGS)
                }
                else -> Action(R.string.location_action_precise) { locationAsk.askForPrecise() }
            }
            // Precise permission but a weak signal: move or wait, then look again (the list stays).
            PickerNotice.POSITION_ROUGH ->
                R.string.social_add_place_rough to Action(R.string.location_action_retry) { viewModel.onFindPrecisePosition() }
            PickerNotice.NEAR_PRIVATE -> R.string.social_add_place_private to null
            null -> null to null
        }
        if (message != null) binding.pickerNotice.setMessage(getString(message))
        button.isVisible = action != null
        if (action != null) {
            button.setText(action.label)
            button.setOnClickListener { action.run() }
        }
    }

    private class Action(val label: Int, val run: () -> Unit)

    private fun row(row: PlaceRow): PersonRow {
        val category = getString(SocialLabels.of(row.place.category))
        return PersonRow(
            key = row.place.publicId,
            title = row.place.name,
            subtitle = if (row.nearPrivate) getString(R.string.social_place_row, category,
                getString(R.string.social_place_near_private)) else category,
            onOpen = { viewModel.onPick(row.place) },
            openDescription = row.place.name
        )
    }

    private fun finish(result: Bundle) {
        setFragmentResult(RESULT, result)
        findNavController().navigateUp()
    }

    companion object {
        const val RESULT = "placePicker"
        const val KEY_CHOICE = "choice"
        const val CHOICE_PLACE = "place"
        const val CHOICE_CITY = "city"
        const val CHOICE_NONE = "none"
        private const val KEY_ID = "id"
        private const val KEY_NAME = "name"
        private const val KEY_CATEGORY = "category"
        private const val KEY_CELL = "cell"
        private const val KEY_AREA = "area"

        fun placeFrom(bundle: Bundle): Place? {
            return Place(
                publicId = bundle.getString(KEY_ID) ?: return null,
                name = bundle.getString(KEY_NAME) ?: return null,
                category = Interest.fromCode(bundle.getString(KEY_CATEGORY) ?: return null) ?: return null,
                cell = bundle.getString(KEY_CELL) ?: return null,
                areaCell = bundle.getString(KEY_AREA) ?: return null
            )
        }
    }
}
