package com.safesphere.app.feature.social.ui

import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.safesphere.app.R
import com.safesphere.app.appContainer
import com.safesphere.app.core.ui.ViewBindingFragment
import com.safesphere.app.core.ui.collectWhenStarted
import com.safesphere.app.core.ui.showSessionExpiredDialog
import com.safesphere.app.core.ui.toMessage
import com.safesphere.app.databinding.FragmentCocoonBinding
import com.safesphere.app.feature.social.domain.ButterflyState
import com.safesphere.app.feature.social.domain.CocoonItem
import com.safesphere.app.feature.social.domain.TimeBand

/**
 * Phase 13C: the Cocoon (design §3.4, §9.6). Want to experience / Been; "I've been" moves an item and invites a
 * perspective or an experience of the person's own. Reduce-motion friendly: no animation, the state is in words.
 */
class CocoonFragment : ViewBindingFragment<FragmentCocoonBinding>(FragmentCocoonBinding::inflate) {

    private val viewModel by viewModels<CocoonViewModel> { CocoonViewModel.factory(appContainer) }
    private val adapter = PersonRowAdapter()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.itemList.adapter = adapter
        binding.tabGroup.addOnButtonCheckedListener { _, id, checked ->
            if (checked) viewModel.onTab(if (id == R.id.beenTab) ButterflyState.BEEN else ButterflyState.WANT)
        }
        binding.showMoreButton.setOnClickListener { viewModel.onShowMore() }
        collectWhenStarted(viewModel.uiState, ::render)
    }

    override fun onStart() {
        super.onStart()
        viewModel.reload() // a post saved or marked elsewhere shows at once
    }

    private fun render(state: CocoonUiState) {
        binding.progress.isVisible = state.isLoading || state.isBusy
        adapter.submitList(state.items.map { row(it, state.isBusy) })
        binding.emptyText.isVisible = state.loaded && state.items.isEmpty()
        binding.emptyText.setText(if (state.tab == ButterflyState.WANT) R.string.cocoon_empty_want else R.string.cocoon_empty_been)
        binding.showMoreButton.isVisible = state.hasMore
        binding.showMoreButton.isEnabled = !state.isLoading
        val failure = state.failure
        binding.errorNotice.isVisible = failure != null
        if (failure != null) binding.errorNotice.setMessage(failure.toMessage(requireContext()))
        state.beenInvitation?.let {
            viewModel.onBeenInvitationHandled()
            showBeenInvitation(it)
        }
        if (state.sessionExpired) {
            viewModel.onSessionExpiredHandled()
            showSessionExpiredDialog()
        }
    }

    private fun row(item: CocoonItem, busy: Boolean): PersonRow {
        val base = PostRows.of(requireContext(), item.post) { openPost(item.post.publicId) }
        val saved = getString(when (item.savedPeriod) {
            TimeBand.THIS_WEEK -> R.string.cocoon_saved_this_week
            TimeBand.THIS_MONTH -> R.string.cocoon_saved_this_month
            TimeBand.EARLIER -> R.string.cocoon_saved_earlier
        })
        val move = if (item.state == ButterflyState.WANT) {
            RowAction(getString(R.string.cocoon_mark_been), getString(R.string.cocoon_mark_been_description, base.title)) {
                if (!busy) viewModel.onMarkBeen(item)
            }
        } else {
            RowAction(getString(R.string.cocoon_want_again), getString(R.string.cocoon_want_again_description, base.title)) {
                if (!busy) viewModel.onWantAgain(item)
            }
        }
        return base.copy(
            subtitle = base.subtitle + " · " + saved,
            primary = move,
            secondary = RowAction(getString(R.string.cocoon_remove), getString(R.string.cocoon_remove_description, base.title)) {
                if (!busy) viewModel.onRemove(item)
            }
        )
    }

    private fun showBeenInvitation(item: CocoonItem) {
        val builder = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.been_invitation_title)
            .setMessage(R.string.been_invitation_message)
            .setNeutralButton(R.string.been_invitation_later, null)
            .setNegativeButton(R.string.been_invitation_own) { _, _ -> findNavController().navigate(R.id.composeFragment) }
        if (item.post.canComment) {
            builder.setPositiveButton(R.string.been_invitation_perspective) { _, _ -> openPost(item.post.publicId) }
        }
        builder.show()
    }

    private fun openPost(postId: String) =
        findNavController().navigate(R.id.postFragment, bundleOf(PostViewModel.ARG_POST_ID to postId))
}
