package com.safesphere.app.feature.social.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.navigation.navOptions
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.safesphere.app.R
import com.safesphere.app.appContainer
import com.safesphere.app.core.ui.SpokenText
import com.safesphere.app.core.ui.ViewBindingFragment
import com.safesphere.app.core.ui.collectWhenStarted
import com.safesphere.app.core.ui.components.ButterflyDrawable
import com.safesphere.app.core.ui.showSessionExpiredDialog
import com.safesphere.app.core.ui.toMessage
import com.safesphere.app.databinding.FragmentCocoonBinding
import com.safesphere.app.databinding.ItemCocoonBinding
import com.safesphere.app.feature.going.GoingThere
import com.safesphere.app.feature.social.domain.ButterflyState
import com.safesphere.app.feature.social.domain.CocoonItem
import com.safesphere.app.feature.social.domain.TimeBand

/**
 * Phase 13C, redesigned in 13F (F16): the Cocoon tab. Want to go (chrysalises) / Been (butterflies). "I've been" lets
 * the butterfly out ([BeenInvitation.emerge]), moves the item, then invites a word for the next person. Private: nothing
 * here is shown to anyone. [CocoonViewModel] is unchanged.
 */
class CocoonFragment : ViewBindingFragment<FragmentCocoonBinding>(FragmentCocoonBinding::inflate) {

    private val viewModel by viewModels<CocoonViewModel> { CocoonViewModel.factory(appContainer) }
    private val adapter = CocoonAdapter()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.itemList.adapter = adapter
        binding.showMoreButton.setOnClickListener { viewModel.onShowMore() }
        binding.emptyDiscoverButton.setOnClickListener {
            findNavController().navigate(R.id.discoverFragment, null, navOptions {
                launchSingleTop = true
                restoreState = true
                popUpTo(R.id.discoverFragment) { saveState = true }
            })
        }
        collectWhenStarted(viewModel.uiState, ::render)
    }

    override fun onStart() {
        super.onStart()
        viewModel.reload() // a post saved or marked elsewhere shows at once
    }

    private fun render(state: CocoonUiState) {
        binding.tabs.setTabs(listOf(getString(R.string.cocoon_tab_want), getString(R.string.cocoon_tab_been)),
            if (state.tab == ButterflyState.BEEN) 1 else 0) { index ->
            viewModel.onTab(if (index == 1) ButterflyState.BEEN else ButterflyState.WANT)
        }
        binding.progress.isVisible = state.isLoading || state.isBusy
        adapter.busy = state.isBusy
        adapter.submitList(state.items)
        binding.emptyBlock.isVisible = state.loaded && state.items.isEmpty()
        binding.emptyText.setText(if (state.tab == ButterflyState.WANT) R.string.cocoon_empty_want else R.string.cocoon_empty_been)
        binding.emptyDiscoverButton.isVisible = state.tab == ButterflyState.WANT
        binding.showMoreButton.isVisible = state.hasMore
        binding.showMoreButton.isEnabled = !state.isLoading
        val failure = state.failure
        binding.errorNotice.isVisible = failure != null
        if (failure != null) binding.errorNotice.setMessage(failure.toMessage(requireContext()))
        state.beenInvitation?.let { item ->
            viewModel.onBeenInvitationHandled()
            BeenInvitation.show(this, item.post.canComment,
                onPerspective = { openPost(item.post.publicId, writeBeenHere = true) },
                onOwn = { findNavController().navigate(R.id.composeFragment) })
        }
        if (state.sessionExpired) {
            viewModel.onSessionExpiredHandled()
            showSessionExpiredDialog()
        }
    }

    private fun openPost(postId: String, writeBeenHere: Boolean = false) =
        findNavController().navigate(R.id.postFragment, bundleOf(PostViewModel.ARG_POST_ID to postId,
            PostViewModel.ARG_WRITE_BEEN_HERE to writeBeenHere))

    private inner class CocoonAdapter : ListAdapter<CocoonItem, CocoonAdapter.Holder>(Diff) {

        var busy = false

        inner class Holder(val binding: ItemCocoonBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemCocoonBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = getItem(position)
            val b = holder.binding
            val context = b.root.context
            val card = ExperienceCards.of(item.post, collapseStrongLanguage = false)
            val title = card.headline ?: card.collapse?.let { context.getString(CardTexts.collapsedLine(it)) }.orEmpty()
            val saved = context.getString(when (item.savedPeriod) {
                TimeBand.THIS_WEEK -> R.string.cocoon_saved_this_week
                TimeBand.THIS_MONTH -> R.string.cocoon_saved_this_month
                TimeBand.EARLIER -> R.string.cocoon_saved_earlier
            })
            b.headline.text = title
            b.meta.text = listOfNotNull(CardTexts.whereLine(context, card), saved).joinToString(" · ")
            b.glyph.scaleX = 1f
            b.glyph.scaleY = 1f
            b.glyph.rotation = 0f
            if (item.state == ButterflyState.WANT) b.glyph.setImageResource(R.drawable.ic_chrysalis)
            else b.glyph.setImageDrawable(ButterflyDrawable.intent(context, 40))
            b.openArea.contentDescription = context.getString(R.string.cocoon_item_description,
                SpokenText.part(title), SpokenText.part(b.meta.text),
                context.getString(if (item.state == ButterflyState.WANT) R.string.cocoon_tab_want else R.string.cocoon_tab_been))
            b.openArea.setOnClickListener { openPost(item.post.publicId) }

            if (item.state == ButterflyState.WANT) {
                b.moveButton.setText(R.string.cocoon_mark_been)
                b.moveButton.contentDescription = context.getString(R.string.cocoon_mark_been_description, title)
                b.moveButton.setOnClickListener {
                    if (!busy) BeenInvitation.emerge(b.glyph) { viewModel.onMarkBeen(item) }
                }
            } else {
                b.moveButton.setText(R.string.cocoon_want_again)
                b.moveButton.contentDescription = context.getString(R.string.cocoon_want_again_description, title)
                b.moveButton.setOnClickListener { if (!busy) viewModel.onWantAgain(item) }
            }
            b.removeButton.contentDescription = context.getString(R.string.cocoon_remove_description, title)
            b.removeButton.setOnClickListener { if (!busy) viewModel.onRemove(item) }
            // Phase 13F (F9): a Want item is the moment someone may actually go.
            b.goingButton.isVisible = item.state == ButterflyState.WANT
            b.goingButton.setOnClickListener {
                GoingThere.show(this@CocoonFragment, GoingThere.Target(CardTexts.goingLabel(card)),
                    GoingThere.CocoonChoice(saved = true) { })
            }
        }
    }

    private object Diff : DiffUtil.ItemCallback<CocoonItem>() {
        override fun areItemsTheSame(old: CocoonItem, new: CocoonItem) = old.post.publicId == new.post.publicId
        override fun areContentsTheSame(old: CocoonItem, new: CocoonItem) = old == new
    }
}
