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
import com.safesphere.app.feature.going.GoingThere
import com.safesphere.app.feature.social.domain.Cities
import com.safesphere.app.feature.social.domain.Interest

/**
 * Phase 13C: a place page (C10) or a category page (C11). The place page shows the place's name, category and city,
 * then its exact-place experiences shared for everyone. No rankings (design §3.5). Since 17G-2 (U5) it may show ONE guarded average
 * rating from people who went, without any count; it never orders anything.
 */
class PostCollectionFragment : ViewBindingFragment<FragmentPostCollectionBinding>(FragmentPostCollectionBinding::inflate) {

    private val viewModel by viewModels<PostCollectionViewModel> { PostCollectionViewModel.factory(appContainer) }
    private lateinit var adapter: ExperienceCardAdapter
    private var collapseStrongLanguage = false

    /** Phase 17G-2 (U5): "How was it?" after "I went" (the person's own visit only). */
    private var wentSheet: WentSheet? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = ExperienceCardAdapter(cardImages())
        binding.postList.adapter = adapter
        collectWhenStarted(appContainer.viewerPreferences.collapseStrongLanguage) { on ->
            if (on != collapseStrongLanguage) {
                collapseStrongLanguage = on
                render(viewModel.uiState.value)
            }
        }
        binding.showMoreButton.setOnClickListener { viewModel.onShowMore() }
        wentSheet = WentSheet(this, onRate = viewModel::onRate, onClearRating = viewModel::onClearRating,
            onNotWent = viewModel::onNotWent, onShare = {
                viewModel.uiState.value.place?.let { place ->
                    findNavController().navigate(R.id.composeFragment, bundleOf(
                        ComposeFragment.ARG_PLACE_ID to place.publicId, ComposeFragment.ARG_CATEGORY to place.category.name))
                }
            })
        collectWhenStarted(viewModel.uiState, ::render)
        val args = arguments
        val placeId = args?.getString(PostCollectionViewModel.ARG_PLACE_ID)
        val category = args?.getString(PostCollectionViewModel.ARG_CATEGORY)?.let(Interest::fromCode)
        val city = Cities.listCity(args?.getString(PostCollectionViewModel.ARG_CITY))
        when {
            placeId != null -> viewModel.show(PostCollection.OfPlace(placeId))
            category != null -> viewModel.show(PostCollection.OfCategory(category, city))
        }
    }

    private fun render(state: PostCollectionUiState) {
        when (val c = state.collection) {
            is PostCollection.OfPlace -> {
                val place = state.place
                binding.heading.text = place?.name.orEmpty()
                // Phase 13F (F9): a place page is where someone decides to go; 17E: or shares their own experience.
                binding.kindTag.text = place?.let { getString(SocialLabels.of(it.category)) }.orEmpty()
                binding.kindTag.isVisible = place != null
                binding.placeActions.isVisible = place != null
                binding.goingButton.setOnClickListener {
                    if (place != null) GoingThere.show(this, GoingThere.Target(listOfNotNull(place.name,
                        place.city?.name).joinToString(", ")), cocoon = null)
                }
                // Phase 17G-2 (U5): "I went here" marks a private record, then asks how it was (and offers the composer).
                val visit = state.visit
                // The spoken label follows the state the text shows (found with TalkBack on the A23: it kept the first-visit prompt).
                binding.wentButton.contentDescription = place?.let {
                    when {
                        visit == null -> getString(R.string.place_share_yours_description, it.name)
                        visit.rating == null -> getString(R.string.place_went_description_state, it.name)
                        else -> getString(R.string.place_went_description_rated, it.name, visit.rating.toString())
                    }
                }
                binding.wentButton.text = when {
                    visit == null -> getString(R.string.place_share_yours)
                    visit.rating == null -> getString(R.string.place_went_state)
                    else -> getString(R.string.place_went_rated, visit.rating.toString())
                }
                binding.wentButton.isEnabled = !state.visitBusy
                binding.wentButton.setOnClickListener { if (visit == null) viewModel.onWent() else viewModel.onOpenVisitSheet() }
                // The one public average: words and a drawn star, never a count, never ordering anything.
                val rating = state.rating
                binding.ratingLine.isVisible = rating != null
                if (rating != null) {
                    binding.ratingLine.text = getString(R.string.place_rating_line, rating)
                    binding.ratingLine.contentDescription = getString(R.string.place_rating_description, rating)
                }
                binding.detailLine.text = if (place == null) "" else listOfNotNull(place.city?.name,
                    getString(R.string.place_page_note)).joinToString(" · ")
            }
            is PostCollection.OfCategory -> {
                binding.heading.text = getString(SocialLabels.of(c.category))
                binding.kindTag.isVisible = false
                binding.placeActions.isVisible = false
                binding.ratingLine.isVisible = false
                binding.detailLine.text = c.city?.let { getString(R.string.collection_category_city_line, it.name) }
                    ?: getString(R.string.collection_category_line)
            }
            null -> Unit
        }
        binding.progress.isVisible = state.isLoading
        wentSheet?.setBusy(state.visitBusy)
        if (state.visitSheet) {
            viewModel.onVisitSheetHandled()
            wentSheet?.show(state.visit)
        } else if (wentSheet?.isShowing == true) {
            wentSheet?.update(state.visit)
        }
        adapter.submitList(state.items.map { post ->
            CardRow(ExperienceCards.of(post, collapseStrongLanguage = collapseStrongLanguage), onOpen = {
                findNavController().navigate(R.id.postFragment, bundleOf(PostViewModel.ARG_POST_ID to post.publicId))
            }, onReact = { viewModel.marks.react(post) }, onSave = { viewModel.marks.save(post) }, onComments = {
                findNavController().navigate(R.id.postFragment, bundleOf(PostViewModel.ARG_POST_ID to post.publicId,
                    PostViewModel.ARG_TO_COMMENTS to true))
            })
        })
        binding.emptyBlock.isVisible = state.loaded && state.items.isEmpty()
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
