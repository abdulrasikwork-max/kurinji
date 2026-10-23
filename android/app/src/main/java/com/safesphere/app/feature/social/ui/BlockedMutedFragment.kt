package com.safesphere.app.feature.social.ui

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.safesphere.app.R
import com.safesphere.app.appContainer
import com.safesphere.app.core.ui.ViewBindingFragment
import com.safesphere.app.core.ui.collectWhenStarted
import com.safesphere.app.core.ui.showSessionExpiredDialog
import com.safesphere.app.core.ui.toMessage
import com.safesphere.app.databinding.FragmentBlockedMutedBinding
import com.safesphere.app.feature.social.domain.PersonSummary

/** Phase 13A: the person's own blocked and muted lists. Unblocking asks first; it does not reconnect anyone. */
class BlockedMutedFragment : ViewBindingFragment<FragmentBlockedMutedBinding>(FragmentBlockedMutedBinding::inflate) {

    private val viewModel by viewModels<BlockedMutedViewModel> { BlockedMutedViewModel.factory(appContainer) }

    private val blockedAdapter = PersonRowAdapter()
    private val mutedAdapter = PersonRowAdapter()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.blockedList.adapter = blockedAdapter
        binding.mutedList.adapter = mutedAdapter
        collectWhenStarted(viewModel.uiState, ::render)
    }

    private fun render(state: BlockedMutedUiState) {
        binding.progress.isVisible = state.isLoading
        blockedAdapter.submitList(state.blocked.map { person ->
            row(person, RowAction(getString(R.string.social_unblock), getString(R.string.social_unblock_cd, person.displayName)) {
                confirmUnblock(person)
            })
        })
        mutedAdapter.submitList(state.muted.map { person ->
            row(person, RowAction(getString(R.string.social_unmute), getString(R.string.social_unmute_cd, person.displayName)) {
                viewModel.onUnmute(person)
            })
        })
        binding.blockedEmpty.isVisible = state.hasLoaded && state.blocked.isEmpty()
        binding.mutedEmpty.isVisible = state.hasLoaded && state.muted.isEmpty()

        val failure = state.failure
        binding.errorNotice.isVisible = failure != null
        if (failure != null) binding.errorNotice.setMessage(failure.toMessage(requireContext()))
        if (state.sessionExpired) {
            viewModel.onSessionExpiredHandled()
            showSessionExpiredDialog()
        }
    }

    private fun row(person: PersonSummary, action: RowAction) = PersonRow(
        key = person.publicId,
        title = person.displayName,
        subtitle = getString(R.string.social_handle_line, person.handle),
        secondary = action
    )

    private fun confirmUnblock(person: PersonSummary) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.social_unblock_title, person.displayName))
            .setMessage(R.string.social_unblock_message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.social_unblock) { _, _ -> viewModel.onUnblockConfirmed(person) }
            .show()
    }
}
