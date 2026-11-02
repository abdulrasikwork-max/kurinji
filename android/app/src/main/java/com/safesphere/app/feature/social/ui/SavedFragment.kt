package com.safesphere.app.feature.social.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load
import com.safesphere.app.R
import com.safesphere.app.appContainer
import com.safesphere.app.core.ui.ViewBindingFragment
import com.safesphere.app.core.ui.collectWhenStarted
import com.safesphere.app.core.ui.showSessionExpiredDialog
import com.safesphere.app.core.ui.toMessage
import com.safesphere.app.databinding.FragmentSavedBinding
import com.safesphere.app.databinding.ItemSavedBinding
import com.safesphere.app.feature.going.GoingThere
import com.safesphere.app.feature.social.domain.Precision
import com.safesphere.app.feature.social.domain.SavedItem
import com.safesphere.app.feature.social.domain.TimeBand

/**
 * Phase 17E (D-3): the Saved tab (nav id `cocoonFragment`, kept). The person's own bookmarks, newest first; each opens
 * its post, "I went" opens the composer with its place, "Going there?" the bridge sheet, Remove takes it out (put back
 * if the server says no). Loaded again whenever the tab is shown (saves happen elsewhere); nothing is stored.
 */
class SavedFragment : ViewBindingFragment<FragmentSavedBinding>(FragmentSavedBinding::inflate) {

    private val viewModel by viewModels<SavedViewModel> { SavedViewModel.factory(appContainer) }
    private lateinit var adapter: Adapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = Adapter()
        binding.list.adapter = adapter
        binding.list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val manager = recyclerView.layoutManager as LinearLayoutManager
                if (manager.findLastVisibleItemPosition() >= adapter.itemCount - 3) viewModel.onShowMore()
            }
        })
        binding.retryButton.setOnClickListener { viewModel.reload() }
        binding.emptyDiscoverButton.setOnClickListener {
            requireActivity().findViewById<View>(R.id.discoverFragment)?.performClick()
        }
        collectWhenStarted(viewModel.uiState, ::render)
    }

    override fun onStart() {
        super.onStart()
        viewModel.reload()
    }

    private fun render(state: SavedUiState) {
        adapter.submitList(state.items)
        binding.progress.isVisible = state.isLoading
        binding.emptyBlock.isVisible = state.isEmpty && state.failure == null
        val failure = state.failure
        binding.errorNotice.isVisible = failure != null
        binding.retryButton.isVisible = failure != null
        if (failure != null) binding.errorNotice.setMessage(failure.toMessage(requireContext()))
        if (state.sessionExpired) {
            viewModel.onSessionExpiredHandled()
            showSessionExpiredDialog()
        }
    }

    private fun open(item: SavedItem) = findNavController().navigate(R.id.postFragment,
        bundleOf(PostViewModel.ARG_POST_ID to item.post.publicId))

    private fun went(item: SavedItem) {
        val post = item.post
        findNavController().navigate(R.id.composeFragment, bundleOf(
            ComposeFragment.ARG_PLACE_ID to post.placeId.takeIf { post.precision == Precision.EXACT },
            ComposeFragment.ARG_CATEGORY to post.category?.name))
    }

    private inner class Adapter : ListAdapter<SavedItem, Adapter.Holder>(Diff) {

        inner class Holder(val binding: ItemSavedBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val binding = ItemSavedBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            // Phase 17E: with large text on a narrow screen, I went / Going there? / Remove go one under the other
            // instead of pushing Remove off the card.
            val config = parent.resources.configuration
            if (MarksFit.mode(config.screenWidthDp, config.fontScale) == MarksFit.Mode.ICONS) {
                binding.actions.orientation = android.widget.LinearLayout.VERTICAL
                binding.actions.gravity = android.view.Gravity.START
            }
            return Holder(binding)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = getItem(position)
            val b = holder.binding
            val context = b.root.context
            val card = ExperienceCards.of(item.post)
            val photo = card.photo
            b.photo.isVisible = photo != null
            b.tile.isVisible = photo == null
            if (photo != null) {
                b.photo.load(appContainer.experienceRepository.photoUrl(photo.mediaId), appContainer.imageLoader) {
                    crossfade(true)
                }
            } else {
                b.photo.dispose()
                b.photo.setImageDrawable(null)
            }
            val words = card.thought ?: card.collapse?.let { context.getString(CardTexts.collapsedLine(it)) }
            b.tile.text = com.safesphere.app.core.ui.components.AvatarView.initialOf(words ?: card.authorName)
            b.title.text = words.orEmpty()
            b.who.text = listOfNotNull(card.authorName, CardTexts.placeChip(context, card)).joinToString(" · ")
            b.savedWhen.text = context.getString(when (item.savedPeriod) {
                TimeBand.THIS_WEEK -> R.string.cocoon_saved_this_week
                TimeBand.THIS_MONTH -> R.string.cocoon_saved_this_month
                TimeBand.EARLIER -> R.string.cocoon_saved_earlier
            })
            b.content.contentDescription = listOf(CardTexts.spoken(context, card), b.savedWhen.text)
                .joinToString(". ")
            b.content.setOnClickListener { open(item) }
            // "I went" for experiences (a question is not a place someone went to); "Going there?" for any place.
            b.wentButton.isVisible = !card.isAsk
            b.wentButton.setOnClickListener { went(item) }
            b.goingButton.setOnClickListener {
                GoingThere.show(this@SavedFragment, GoingThere.Target(CardTexts.goingLabel(card)), null)
            }
            b.removeButton.contentDescription = context.getString(R.string.saved_remove_description,
                words?.take(40).orEmpty())
            b.removeButton.setOnClickListener { viewModel.onRemove(item) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<SavedItem>() {
        override fun areItemsTheSame(old: SavedItem, new: SavedItem) = old.post.publicId == new.post.publicId
        override fun areContentsTheSame(old: SavedItem, new: SavedItem) = old == new
    }
}
