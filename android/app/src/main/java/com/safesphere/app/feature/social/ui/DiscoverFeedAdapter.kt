package com.safesphere.app.feature.social.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.ImageLoader
import com.google.android.material.chip.Chip
import com.safesphere.app.R
import com.safesphere.app.databinding.ItemExperienceCardBinding
import com.safesphere.app.databinding.ItemFeedHeaderBinding
import com.safesphere.app.databinding.ItemFeedLoadingBinding
import com.safesphere.app.databinding.ItemFeedMessageBinding
import com.safesphere.app.databinding.ItemFeedRailBinding
import com.safesphere.app.databinding.ItemFeedShortcutsBinding
import com.safesphere.app.databinding.ItemRailCardBinding
import com.safesphere.app.feature.social.domain.DiscoverItem
import com.safesphere.app.feature.social.domain.Interest
import com.safesphere.app.feature.social.domain.Post

/** What the Discover feed's buttons do (the fragment navigates; the ViewModel saves). */
interface FeedActions {
    fun openPost(post: Post, toComments: Boolean)
    fun save(post: Post)
    fun why(item: DiscoverItem)
    fun nearMe()
    fun category(interest: Interest)
    fun share()
    fun findPeople()
    fun chooseCity()
}

/** A feed row and the strong-language choice that changes how its post card looks. */
data class FeedRow(val item: FeedItem, val collapseStrongLanguage: Boolean)

/**
 * Phase 16: the ONE RecyclerView of Discover (no nested scrolling lists): every row type of [DiscoverFeed], recycled.
 */
class DiscoverFeedAdapter(
    private val loader: ImageLoader,
    private val photoUrl: (String) -> String,
    private val actions: FeedActions
) : ListAdapter<FeedRow, RecyclerView.ViewHolder>(Diff) {

    private class Bound<B : androidx.viewbinding.ViewBinding>(val binding: B) : RecyclerView.ViewHolder(binding.root)

    override fun getItemViewType(position: Int): Int = when (getItem(position).item) {
        FeedItem.Shortcuts -> SHORTCUTS
        is FeedItem.Header -> HEADER
        is FeedItem.Talking -> RAIL
        is FeedItem.PostRow -> POST
        FeedItem.Loading -> LOADING
        FeedItem.ConnectPrompt, FeedItem.ChooseCity, FeedItem.End, FeedItem.Empty -> MESSAGE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            SHORTCUTS -> Bound(ItemFeedShortcutsBinding.inflate(inflater, parent, false)).also { fillShortcuts(it.binding) }
            HEADER -> Bound(ItemFeedHeaderBinding.inflate(inflater, parent, false))
            RAIL -> Bound(ItemFeedRailBinding.inflate(inflater, parent, false)).also {
                it.binding.rail.adapter = RailAdapter(actions)
            }
            POST -> Bound(ItemExperienceCardBinding.inflate(inflater, parent, false)).also {
                val margin = parent.resources.getDimensionPixelSize(R.dimen.screen_padding)
                (it.binding.root.layoutParams as ViewGroup.MarginLayoutParams).apply {
                    marginStart = margin
                    marginEnd = margin
                }
            }
            LOADING -> Bound(ItemFeedLoadingBinding.inflate(inflater, parent, false))
            else -> Bound(ItemFeedMessageBinding.inflate(inflater, parent, false))
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val row = getItem(position)
        val context = holder.itemView.context
        when (val item = row.item) {
            is FeedItem.Header -> (holder as Bound<ItemFeedHeaderBinding>).binding.let { b ->
                b.title.text = when (item.kind) {
                    HeaderKind.TALKING -> context.getString(R.string.feed_header_talking)
                    HeaderKind.CITY -> context.getString(R.string.discover_section_city,
                        item.city?.let(ExperienceLabels::cityName).orEmpty())
                    HeaderKind.THEME -> item.theme?.let { context.getString(R.string.feed_header_theme,
                        context.getString(SocialLabels.of(it))) } ?: context.getString(R.string.discover_section_theme)
                    HeaderKind.CONNECTIONS -> context.getString(R.string.feed_header_connections)
                }
                val subtitle = when (item.kind) {
                    HeaderKind.TALKING -> R.string.feed_header_talking_sub
                    HeaderKind.CONNECTIONS -> R.string.feed_header_connections_sub
                    else -> null
                }
                b.subtitle.isVisible = subtitle != null
                if (subtitle != null) b.subtitle.setText(subtitle)
            }
            is FeedItem.Talking -> ((holder as Bound<ItemFeedRailBinding>).binding.rail.adapter as RailAdapter)
                .submitList(item.items)
            is FeedItem.PostRow -> {
                val post = item.item.post
                val card = ExperienceCards.of(post, item.item.reasons, row.collapseStrongLanguage)
                CardBinder.bind((holder as Bound<ItemExperienceCardBinding>).binding, CardRow(card,
                    onOpen = { actions.openPost(post, toComments = false) },
                    onWhy = if (item.item.reasons.isNotEmpty() &&
                        item.item.reasons != listOf(com.safesphere.app.feature.social.domain.DiscoverReason.FROM_A_CONNECTION))
                        ({ actions.why(item.item) }) else null,
                    onSave = { actions.save(post) },
                    onComments = { actions.openPost(post, toComments = true) }), loader, photoUrl)
            }
            FeedItem.End -> message(holder, R.string.discover_edition_end, R.string.feed_end_message,
                R.string.feed_share_yours) { actions.share() }
            FeedItem.Empty -> message(holder, R.string.feed_empty_title, R.string.discover_edition_empty,
                R.string.feed_share_yours) { actions.share() }
            FeedItem.ConnectPrompt -> message(holder, R.string.feed_connect_title, R.string.feed_connect_message,
                R.string.feed_connect_action) { actions.findPeople() }
            FeedItem.ChooseCity -> message(holder, R.string.discover_masthead_no_city, R.string.feed_choose_city_message,
                null) { }
            FeedItem.Shortcuts, FeedItem.Loading -> Unit
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun message(holder: RecyclerView.ViewHolder, title: Int, message: Int, action: Int?, onAction: () -> Unit) {
        val b = (holder as Bound<ItemFeedMessageBinding>).binding
        b.title.setText(title)
        b.message.isVisible = true
        b.message.setText(message)
        b.action.isVisible = action != null
        if (action != null) b.action.setText(action)
        b.action.setOnClickListener { onAction() }
    }

    private fun fillShortcuts(b: ItemFeedShortcutsBinding) {
        val context = b.root.context
        b.shortcutGroup.addView(Chip(context, null, R.attr.pillChipStyle).apply {
            text = context.getString(R.string.discover_near_me)
            setChipIconResource(R.drawable.ic_place)
            isChipIconVisible = true
            isCheckable = false
            setOnClickListener { actions.nearMe() }
        })
        Interest.entries.forEach { interest ->
            b.shortcutGroup.addView(Chip(context, null, R.attr.pillChipStyle).apply {
                text = context.getString(SocialLabels.of(interest))
                isCheckable = false
                contentDescription = context.getString(R.string.discover_open_category, text)
                setOnClickListener { actions.category(interest) }
            })
        }
    }

    private object Diff : DiffUtil.ItemCallback<FeedRow>() {
        override fun areItemsTheSame(old: FeedRow, new: FeedRow): Boolean = key(old.item) == key(new.item)
        override fun areContentsTheSame(old: FeedRow, new: FeedRow) = old == new

        private fun key(item: FeedItem): String = when (item) {
            is FeedItem.Header -> "header:${item.kind}"
            is FeedItem.Talking -> "talking"
            is FeedItem.PostRow -> "post:${item.item.post.publicId}"
            else -> item.javaClass.simpleName
        }
    }

    private companion object {
        const val SHORTCUTS = 0
        const val HEADER = 1
        const val RAIL = 2
        const val POST = 3
        const val LOADING = 4
        const val MESSAGE = 5
    }
}

/** Phase 16 (U2): the small cards of "Talking now". */
private class RailAdapter(private val actions: FeedActions) :
    ListAdapter<DiscoverItem, RailAdapter.Holder>(RailDiff) {

    class Holder(val binding: ItemRailCardBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemRailCardBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = getItem(position)
        val b = holder.binding
        val context = b.root.context
        val card = ExperienceCards.of(item.post, item.reasons)
        b.avatar.set(card.authorName, card.authorHandle)
        b.name.text = card.authorName ?: context.getString(R.string.feed_someone)
        b.thought.text = card.thought ?: card.collapse?.let { context.getString(CardTexts.collapsedLine(it)) }.orEmpty()
        b.comments.text = context.resources.getQuantityString(R.plurals.feed_comments, card.commentCount,
            card.commentCount).let { if (card.commentCount == 0) context.getString(R.string.feed_comment) else it }
        b.card.contentDescription = listOf(CardTexts.spoken(context, card), CardTexts.commentsSpoken(context,
            card.commentCount)).joinToString(". ")
        b.card.setOnClickListener { actions.openPost(item.post, toComments = true) }
    }

    private object RailDiff : DiffUtil.ItemCallback<DiscoverItem>() {
        override fun areItemsTheSame(old: DiscoverItem, new: DiscoverItem) = old.post.publicId == new.post.publicId
        override fun areContentsTheSame(old: DiscoverItem, new: DiscoverItem) = old == new
    }
}

