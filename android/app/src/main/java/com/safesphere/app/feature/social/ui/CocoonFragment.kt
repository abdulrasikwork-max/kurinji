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
import coil.load
import com.google.android.material.shape.ShapeAppearanceModel
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
 * Phase 13C, simplified in Phase 16 (docs/PHASE16.md): the Saved tab (13F called it the Cocoon). Two plain lists, Want
 * to go and Been there; "I've been" moves an item (the butterfly emerges) and invites a comment for others. Private:
 * nothing here is shown to anyone. [CocoonViewModel] is unchanged.
 */
class CocoonFragment : ViewBindingFragment<FragmentCocoonBinding>(FragmentCocoonBinding::inflate) {

    private val viewModel by viewModels<CocoonViewModel> { CocoonViewModel.factory(appContainer) }
    private val adapter = CocoonAdapter()
    private lateinit var tabs: ChoiceChips<ButterflyState>

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.itemList.adapter = adapter
        tabs = ChoiceChips(binding.tabGroup) { it?.let(viewModel::onTab) }
        binding.showMoreButton.setOnClickListener { viewModel.onShowMore() }
        binding.emptyDiscoverButton.setOnClickListener {
            findNavController().navigate(R.id.discoverFragment, null, navOptions {
                launchSingleTop = true
                restoreState = true
                popUpTo(R.id.discoverFragment) { saveState = true }
            })
        }
        binding.emptyGlyph.setImageDrawable(ButterflyDrawable.outline(requireContext(), 72))
        collectWhenStarted(viewModel.uiState, ::render)
    }

    override fun onStart() {
        super.onStart()
        viewModel.reload() // a post saved or marked elsewhere shows at once
    }

    private fun render(state: CocoonUiState) {
        tabs.show(listOf(ButterflyState.WANT to getString(R.string.cocoon_tab_want),
            ButterflyState.BEEN to getString(R.string.cocoon_tab_been)), state.tab)
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

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val holder = Holder(ItemCocoonBinding.inflate(LayoutInflater.from(parent.context), parent, false))
            holder.binding.photo.shapeAppearanceModel = ShapeAppearanceModel.builder()
                .setAllCornerSizes(parent.resources.getDimension(R.dimen.space_md)).build()
            return holder
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = getItem(position)
            val b = holder.binding
            val context = b.root.context
            val card = ExperienceCards.of(item.post, collapseStrongLanguage = false)
            val title = card.thought ?: card.collapse?.let { context.getString(CardTexts.collapsedLine(it)) }.orEmpty()
            val saved = context.getString(when (item.savedPeriod) {
                TimeBand.THIS_WEEK -> R.string.cocoon_saved_this_week
                TimeBand.THIS_MONTH -> R.string.cocoon_saved_this_month
                TimeBand.EARLIER -> R.string.cocoon_saved_earlier
            })
            b.headline.text = title
            b.meta.text = listOfNotNull(card.authorName, CardTexts.placeChip(context, card), saved).joinToString(" · ")
            b.avatar.set(card.authorName, card.authorHandle)
            val photo = card.photo
            b.photo.isVisible = photo != null
            if (photo != null) b.photo.load(appContainer.experienceRepository.photoUrl(photo.mediaId), appContainer.imageLoader)
            else b.photo.setImageDrawable(null)
            b.openArea.contentDescription = context.getString(R.string.cocoon_item_description,
                SpokenText.part(title), SpokenText.part(b.meta.text),
                context.getString(if (item.state == ButterflyState.WANT) R.string.cocoon_tab_want else R.string.cocoon_tab_been))
            b.openArea.setOnClickListener { openPost(item.post.publicId) }

            if (item.state == ButterflyState.WANT) {
                b.moveButton.setText(R.string.cocoon_mark_been)
                b.moveButton.setIconResource(R.drawable.ic_check)
                b.moveButton.contentDescription = context.getString(R.string.cocoon_mark_been_description, title)
                b.moveButton.setOnClickListener {
                    if (!busy) BeenInvitation.bounce(b.avatar.parent as View) { viewModel.onMarkBeen(item) }
                }
            } else {
                b.moveButton.setText(R.string.cocoon_want_again)
                b.moveButton.icon = ButterflyDrawable.outline(context, 22)
                b.moveButton.iconTint = null
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
