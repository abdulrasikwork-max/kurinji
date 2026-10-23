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
import com.safesphere.app.databinding.FragmentPostCollectionBinding
import com.safesphere.app.feature.social.domain.City
import com.safesphere.app.feature.social.domain.Interest

/**
 * Phase 13C: a place page (C10) or a category page (C11). The place page shows the place's name, category and city,
 * then its exact-place experiences shared for everyone. No stars, no ratings, no rankings (design §3.5).
 */
class PostCollectionFragment : ViewBindingFragment<FragmentPostCollectionBinding>(FragmentPostCollectionBinding::inflate) {

    private val viewModel by viewModels<PostCollectionViewModel> { PostCollectionViewModel.factory(appContainer) }
    private val adapter = PersonRowAdapter()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.postList.adapter = adapter
        binding.showMoreButton.setOnClickListener { viewModel.onShowMore() }
        collectWhenStarted(viewModel.uiState, ::render)
        val args = arguments
        val placeId = args?.getString(PostCollectionViewModel.ARG_PLACE_ID)
        val category = args?.getString(PostCollectionViewModel.ARG_CATEGORY)?.let(Interest::fromCode)
        val city = args?.getString(PostCollectionViewModel.ARG_CITY)?.let { c -> City.entries.firstOrNull { it.name == c } }
        when {
            placeId != null -> viewModel.show(PostCollection.OfPlace(placeId))
            category != null -> viewModel.show(PostCollection.OfCategory(category, city?.takeIf { it != City.OTHER }))
        }
    }

    private fun render(state: PostCollectionUiState) {
        when (val c = state.collection) {
            is PostCollection.OfPlace -> {
                val place = state.place
                binding.heading.text = place?.name.orEmpty()
                binding.detailLine.text = if (place == null) "" else listOfNotNull(
                    getString(SocialLabels.of(place.category)), place.city?.let(ExperienceLabels::cityName),
                    getString(R.string.collection_place_line)).joinToString(" · ")
            }
            is PostCollection.OfCategory -> {
                binding.heading.text = getString(SocialLabels.of(c.category))
                binding.detailLine.text = c.city?.let { getString(R.string.collection_category_city_line,
                    ExperienceLabels.cityName(it)) } ?: getString(R.string.collection_category_line)
            }
            null -> Unit
        }
        binding.progress.isVisible = state.isLoading
        adapter.submitList(state.items.map { post ->
            PostRows.of(requireContext(), post) {
                findNavController().navigate(R.id.postFragment, bundleOf(PostViewModel.ARG_POST_ID to post.publicId))
            }
        })
        binding.emptyText.isVisible = state.loaded && state.items.isEmpty()
        binding.emptyText.setText(if (state.notFound) R.string.collection_not_found else R.string.collection_empty)
        binding.showMoreButton.isVisible = state.hasMore
        binding.showMoreButton.isEnabled = !state.isLoading
        binding.endText.isVisible = state.loaded && state.items.isNotEmpty() && !state.hasMore
        val failure = state.failure
        binding.errorNotice.isVisible = failure != null
        if (failure != null) binding.errorNotice.setMessage(failure.toMessage(requireContext()))
        if (state.sessionExpired) {
            viewModel.onSessionExpiredHandled()
            showSessionExpiredDialog()
        }
    }
}
