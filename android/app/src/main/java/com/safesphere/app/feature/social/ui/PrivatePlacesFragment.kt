package com.safesphere.app.feature.social.ui

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.safesphere.app.R
import com.safesphere.app.appContainer
import com.safesphere.app.core.location.LocationPermissions
import com.safesphere.app.core.ui.ViewBindingFragment
import com.safesphere.app.core.ui.collectWhenStarted
import com.safesphere.app.databinding.FragmentPrivatePlacesBinding
import com.safesphere.app.feature.social.domain.PrivatePlaceKind

/**
 * Phase 13B (design §4.2, Q9): private places. Marked from where the person is (one fix, location asked here in
 * context); kept encrypted on this phone, never sent; removed one by one or all at sign-out.
 */
class PrivatePlacesFragment : ViewBindingFragment<FragmentPrivatePlacesBinding>(FragmentPrivatePlacesBinding::inflate) {

    private val viewModel by viewModels<PrivatePlacesViewModel> { PrivatePlacesViewModel.factory(appContainer) }
    private val adapter = PersonRowAdapter()
    private var pendingKind: PrivatePlaceKind? = null

    private val locationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            // Asked again only after a grant (a refusal must never loop back into another request).
            if (result.values.any { it }) pendingKind?.let(viewModel::onPermissionResult)
            else toast(R.string.social_private_needs_precise)
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.placeList.adapter = adapter
        binding.markButton.setOnClickListener { chooseKind() }
        binding.resetButton.setOnClickListener { viewModel.onReset() }
        collectWhenStarted(viewModel.uiState, ::render)
    }

    private fun chooseKind() {
        val kinds = PrivatePlaceKind.entries
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.social_private_choose_kind)
            .setItems(kinds.map { getString(ExperienceLabels.of(it)) }.toTypedArray()) { _, which ->
                pendingKind = kinds[which]
                viewModel.onMarkHere(kinds[which])
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun render(state: PrivatePlacesUiState) {
        binding.progress.isVisible = state.isLoading || state.isBusy
        binding.markButton.isEnabled = !state.isBusy && !state.unreadable
        binding.unreadableNotice.isVisible = state.unreadable
        binding.resetButton.isVisible = state.unreadable
        adapter.submitList(state.places.map { place ->
            val label = getString(ExperienceLabels.of(place.kind))
            PersonRow(
                key = place.id,
                title = label,
                subtitle = "",
                primary = RowAction(getString(R.string.action_remove), getString(R.string.social_private_remove, label)) {
                    viewModel.onRemove(place)
                }
            )
        })
        binding.emptyText.isVisible = !state.isLoading && state.places.isEmpty() && !state.unreadable
        when (state.notice) {
            PrivatePlacesNotice.ADDED -> toast(R.string.social_private_added)
            PrivatePlacesNotice.NEEDS_PERMISSION -> locationLauncher.launch(LocationPermissions.REQUESTED)
            PrivatePlacesNotice.LOCATION_UNAVAILABLE -> toast(R.string.social_location_unavailable)
            PrivatePlacesNotice.NEEDS_PRECISE -> toast(R.string.social_private_needs_precise)
            PrivatePlacesNotice.NOT_SAVED -> toast(R.string.social_private_not_saved)
            null -> Unit
        }
        if (state.notice != null) viewModel.onNoticeShown()
    }

    private fun toast(text: Int) = Toast.makeText(requireContext(), text, Toast.LENGTH_LONG).show()
}
