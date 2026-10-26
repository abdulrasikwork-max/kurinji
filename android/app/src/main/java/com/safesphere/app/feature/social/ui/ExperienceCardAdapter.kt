package com.safesphere.app.feature.social.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.ImageLoader
import coil.load
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.safesphere.app.R
import com.safesphere.app.core.ui.components.ButterflyDrawable
import com.safesphere.app.databinding.ItemExperienceCardBinding
import com.safesphere.app.feature.social.domain.ButterflyState
import com.safesphere.app.feature.social.domain.CityAreas
import java.util.Locale

/**
 * One post in a list and what its buttons do. [onSave] saves (or un-saves) it with the butterfly, right from the feed;
 * null where a list cannot (the button is then hidden). [onComments] opens the post at its comments.
 */
data class CardRow(
    val card: ExperienceCard,
    val onOpen: () -> Unit,
    val onWhy: (() -> Unit)? = null,
    val onSave: (() -> Unit)? = null,
    val onComments: (() -> Unit)? = null
)

/**
 * Phase 16: posts as social feed cards in Discover, place and category pages, profiles and "Your posts". Photos come
 * through the ONE Coil loader (memory cache only, JWT attached).
 */
class ExperienceCardAdapter(
    private val loader: ImageLoader,
    private val photoUrl: (String) -> String
) : ListAdapter<CardRow, ExperienceCardAdapter.Holder>(Diff) {

    class Holder(val binding: ItemExperienceCardBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemExperienceCardBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) =
        CardBinder.bind(holder.binding, getItem(position), loader, photoUrl)

    private object Diff : DiffUtil.ItemCallback<CardRow>() {
        override fun areItemsTheSame(old: CardRow, new: CardRow) = old.card.id == new.card.id
        override fun areContentsTheSame(old: CardRow, new: CardRow) = old.card == new.card &&
            (old.onWhy == null) == (new.onWhy == null) && (old.onSave == null) == (new.onSave == null)
    }
}

/** Phase 16: binds one feed card; shared by every list that shows posts (and Discover's own adapter). */
object CardBinder {

    fun bind(b: ItemExperienceCardBinding, row: CardRow, loader: ImageLoader, photoUrl: (String) -> String) {
        val card = row.card
        val context = b.root.context
        b.avatar.set(card.authorName, card.authorHandle)
        b.name.text = card.authorName ?: context.getString(R.string.feed_someone)
        b.meta.text = CardTexts.meta(context, card)
        b.questionBadge.isVisible = card.isAsk

        val photo = card.photo
        b.photo.isVisible = photo != null
        if (photo != null) {
            b.photo.setPhotoSize(photo.width, photo.height)
            b.photo.load(photoUrl(photo.mediaId), loader) { crossfade(true) }
        } else {
            b.photo.setImageDrawable(null)
        }

        b.thought.isVisible = card.thought != null
        b.thought.text = card.thought.orEmpty()
        b.collapsedLine.isVisible = card.collapse != null
        b.collapsedLine.text = card.collapse?.let { context.getString(CardTexts.collapsedLine(it)) }.orEmpty()
        contextChips(b.contextChips, card, context)

        b.content.setOnClickListener { row.onOpen() }
        b.content.contentDescription = CardTexts.spoken(context, card)

        bindSave(b.saveButton, card, row.onSave)
        b.commentButton.text = if (card.commentCount > 0) String.format(Locale.getDefault(), "%d", card.commentCount)
            else context.getString(R.string.feed_comment)
        b.commentButton.contentDescription = CardTexts.commentsSpoken(context, card.commentCount)
        b.commentButton.setOnClickListener { (row.onComments ?: row.onOpen)() }
        b.whyButton.isVisible = row.onWhy != null
        b.whyButton.setOnClickListener { row.onWhy?.invoke() }
    }

    /** The butterfly: outline = not saved, red = saved; the word says it too (never colour alone). */
    fun bindSave(button: MaterialButton, card: ExperienceCard, onSave: (() -> Unit)?) {
        val context = button.context
        button.isVisible = card.canSave && onSave != null
        val saved = card.intent != null
        button.icon = if (saved) ButterflyDrawable.intent(context, 24) else ButterflyDrawable.outline(context, 24)
        button.iconTint = null
        button.text = context.getString(when (card.intent) {
            null -> R.string.butterfly_want
            ButterflyState.WANT -> R.string.butterfly_saved_want
            ButterflyState.BEEN -> R.string.butterfly_saved_been
        })
        button.contentDescription = context.getString(if (saved) R.string.butterfly_remove_description
            else R.string.butterfly_want_description)
        button.setOnClickListener { onSave?.invoke() }
    }

    private fun contextChips(group: ChipGroup, card: ExperienceCard, context: Context) {
        group.removeAllViews()
        CardTexts.placeChip(context, card)?.let { group.addView(chip(context, it, R.drawable.ic_place)) }
        if (card.hasTip) group.addView(chip(context, context.getString(R.string.feed_tip_chip), R.drawable.ic_star))
        group.isVisible = group.childCount > 0
    }

    private fun chip(context: Context, text: String, icon: Int) =
        Chip(context, null, R.attr.contextChipStyle).apply {
            this.text = text
            setChipIconResource(icon)
            isChipIconVisible = true
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
}

/** The words of a card, built from the pure [ExperienceCard] (also used by the post screen). */
object CardTexts {

    /** Under the name: what kind of post, and when it was shared (a band; own posts add who can see it). */
    fun meta(context: Context, card: ExperienceCard): String = listOfNotNull(
        card.category?.let { context.getString(SocialLabels.of(it)) },
        context.getString(TimeBandTexts.of(card.band)),
        card.audience?.let { context.getString(ExperienceLabels.of(it)) }
    ).joinToString(" · ")

    /** Where, in plain words and only as exactly as the author chose: a place, "Around <city>", a city, or nothing. */
    fun placeChip(context: Context, card: ExperienceCard): String? = when (val place = card.place) {
        is StampPlace.Exact -> place.name
        is StampPlace.Area -> CityAreas.cityOf(place.gridRef)?.let {
            context.getString(R.string.feed_area_in_city, ExperienceLabels.cityName(it))
        } ?: context.getString(R.string.feed_area_unknown)
        is StampPlace.InCity -> ExperienceLabels.cityName(place.city)
        StampPlace.Withheld -> null
    }

    /**
     * Phase 13F: the place name "Going there?" may fill in (a named place or a city). An area is not a destination
     * anyone can look up, so it gives none; the person types one if they want.
     */
    fun goingLabel(card: ExperienceCard): String? = when (val place = card.place) {
        is StampPlace.Exact -> place.name
        is StampPlace.InCity -> ExperienceLabels.cityName(place.city)
        is StampPlace.Area, StampPlace.Withheld -> null
    }

    fun whenLine(context: Context, card: ExperienceCard): String? {
        val month = card.month?.let { ExperienceLabels.monthName(it) }
        val daypart = card.daypart?.let { context.getString(ExperienceLabels.of(it)) }
        return listOfNotNull(month, daypart).takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    fun collapsedLine(collapse: CardCollapse): Int = when (collapse) {
        is CardCollapse.ByModeration -> ModerationLabels.collapsedLine(collapse.collapsed)
        CardCollapse.StrongLanguage -> R.string.collapsed_strong_language
    }

    fun commentsSpoken(context: Context, count: Int): String = if (count == 0)
        context.getString(R.string.feed_comment_first) else
        context.resources.getQuantityString(R.plurals.feed_comments_open, count, count)

    /** One sentence for TalkBack: who, what kind, their words (or why collapsed), where, when, tip, own state. */
    fun spoken(context: Context, card: ExperienceCard): String = listOfNotNull(
        card.authorName,
        if (card.isAsk) context.getString(R.string.feed_question_badge) else null,
        meta(context, card),
        card.thought ?: card.collapse?.let { context.getString(collapsedLine(it)) },
        placeChip(context, card),
        whenLine(context, card),
        if (card.hasTip) context.getString(R.string.feed_tip_chip) else null,
        when (card.intent) {
            ButterflyState.WANT -> context.getString(R.string.butterfly_saved_want)
            ButterflyState.BEEN -> context.getString(R.string.butterfly_saved_been)
            null -> null
        }
    ).map { it.trim().trimEnd('.') }.filter { it.isNotEmpty() }.joinToString(". ") // one full stop between parts
}
