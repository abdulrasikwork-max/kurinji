package com.safesphere.app.feature.social.ui

import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.safesphere.app.R
import com.safesphere.app.appContainer
import com.safesphere.app.core.ui.ViewBindingFragment
import com.safesphere.app.core.ui.collectWhenStarted
import com.safesphere.app.core.ui.showSessionExpiredDialog
import com.safesphere.app.core.ui.toMessage
import com.safesphere.app.databinding.FragmentMyPostsBinding

/** Phase 13B: Your experiences, newest first, with who can see each; more only on "Show more". */
class MyPostsFragment : ViewBindingFragment<FragmentMyPostsBinding>(FragmentMyPostsBinding::inflate) {

    private val viewModel by viewModels<PostListViewModel> { PostListViewModel.factory(appContainer) }
    private val adapter = PersonRowAdapter()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.postList.adapter = adapter
        binding.shareButton.setOnClickListener { findNavController().navigate(R.id.composeFragment) }
        binding.showMoreButton.setOnClickListener { viewModel.onShowMore() }
        collectWhenStarted(viewModel.uiState, ::render)
    }

    override fun onStart() {
        super.onStart()
        viewModel.showOwn() // a post shared or deleted elsewhere shows at once
    }

    private fun render(state: PostListUiState) {
        binding.progress.isVisible = state.isLoading
        adapter.submitList(state.items.map { post ->
            PostRows.of(requireContext(), post) {
                findNavController().navigate(R.id.postFragment, bundleOf(PostViewModel.ARG_POST_ID to post.publicId))
            }
        })
        binding.emptyText.isVisible = state.loaded && state.items.isEmpty()
        binding.showMoreButton.isVisible = state.hasMore
        binding.showMoreButton.isEnabled = !state.isLoading
        val failure = state.failure
        binding.errorNotice.isVisible = failure != null
        if (failure != null) binding.errorNotice.setMessage(failure.toMessage(requireContext()))
        if (state.sessionExpired) {
            viewModel.onSessionExpiredHandled()
            showSessionExpiredDialog()
        }
    }
}
