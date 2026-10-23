package com.safesphere.app.feature.social.ui

import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.safesphere.app.R
import com.safesphere.app.appContainer
import com.safesphere.app.core.location.LocationPermissions
import com.safesphere.app.core.ui.ViewBindingFragment
import com.safesphere.app.core.ui.collectWhenStarted
import com.safesphere.app.core.ui.showSessionExpiredDialog
import com.safesphere.app.core.ui.toMessage
import com.safesphere.app.databinding.FragmentPlacePickerBinding
import com.safesphere.app.feature.social.domain.Interest
import com.safesphere.app.feature.social.domain.Place

/**
 * Phase 13B: where it was. "Places near me" (location asked here, in context, and read once), "Places you used",
 * "Add this place" at the current ~150 m cell, "Only the city", "No place". The choice goes back to the composer.
 */
class PlacePickerFragment : ViewBindingFragment<FragmentPlacePickerBinding>(FragmentPlacePickerBinding::inflate) {

    private val viewModel by viewModels<PlacePickerViewModel> { PlacePickerViewModel.factory(appContainer) }

    private val locationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.any { it }) viewModel.onPermissionResult()
        }

    private val nearbyAdapter = PersonRowAdapter()
    private val recentAdapter = PersonRowAdapter()
    private lateinit var categoryChips: ChoiceChips<Interest>
    private var category: Interest? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.nearbyList.adapter = nearbyAdapter
        binding.recentList.adapter = recentAdapter
        categoryChips = ChoiceChips(binding.placeCategoryGroup) { category = it }
        binding.nearMeButton.setOnClickListener { viewModel.onNearMe() }
        binding.allowLocationButton.setOnClickListener { locationLauncher.launch(LocationPermissions.REQUESTED) }
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

    private fun render(state: PlacePickerUiState) {
        binding.progress.isVisible = state.isBusy
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

        val notice = state.notice ?: if (state.hereCell != null && !state.canAddHere && !state.hereIsPrivate)
            PickerNotice.NEEDS_PRECISE else null
        binding.pickerNotice.isVisible = notice != null
        binding.allowLocationButton.isVisible = notice == PickerNotice.NEEDS_PERMISSION
        when (notice) {
            PickerNotice.NEEDS_PERMISSION -> binding.pickerNotice.setMessage(getString(R.string.social_location_needed))
            PickerNotice.LOCATION_UNAVAILABLE -> binding.pickerNotice.setMessage(getString(R.string.social_location_unavailable))
            PickerNotice.NEEDS_PRECISE -> binding.pickerNotice.setMessage(getString(R.string.social_add_place_needs_precise))
            PickerNotice.NEAR_PRIVATE -> binding.pickerNotice.setMessage(getString(R.string.social_add_place_private))
            null -> Unit
        }

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
