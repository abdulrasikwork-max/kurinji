package com.safesphere.app.feature.social.ui

import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.ImageLoader
import coil.dispose
import coil.load
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import com.safesphere.app.R
import com.safesphere.app.appContainer
import com.safesphere.app.core.ui.components.AvatarView
import com.safesphere.app.core.ui.components.ButterflyDrawable
import com.safesphere.app.databinding.ItemExperienceCardBinding
import com.safesphere.app.feature.social.data.VideoRules
import com.safesphere.app.feature.social.domain.Cities
import java.util.Locale

/**
 * One post in a list and what its marks do. [onReact] gives (or takes back) the butterfly and [onSave] saves (or
 * un-saves) it, right from the list; null where a list cannot (the mark is then hidden). [onComments] opens the post at
 * its comments.
 */
data class CardRow(
    val card: ExperienceCard,
    val onOpen: () -> Unit,
    val onWhy: (() -> Unit)? = null,
    val onReact: (() -> Unit)? = null,
    val onSave: (() -> Unit)? = null,
    val onComments: (() -> Unit)? = null
)

/** Phase 17E: where images come from (the ONE Coil loader; photo and profile-photo addresses). */
class CardImages(
    val loader: ImageLoader,
    val photoUrl: (String) -> String,
    val personPhotoUrl: (String, String) -> String
) {
    /** Shows a person: their photo when they have one, else their initial (the photo is never required). */
    fun avatar(view: AvatarView, name: String?, handle: String?, profileId: String?, photo: String?) {
        val url = if (profileId != null && photo != null) personPhotoUrl(profileId, photo) else null
        view.set(name, handle, url, loader)
    }
}

/**
 * Phase 16, redesigned in 17E: posts as feed cards in Discover, place and category pages, profiles and "Your posts".
 * Photos come through the ONE Coil loader (memory cache only, JWT attached).
 */
class ExperienceCardAdapter(private val images: CardImages) : ListAdapter<CardRow, ExperienceCardAdapter.Holder>(Diff) {

    class Holder(val binding: ItemExperienceCardBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemExperienceCardBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = CardBinder.bind(holder.binding, getItem(position), images)

    private object Diff : DiffUtil.ItemCallback<CardRow>() {
        override fun areItemsTheSame(old: CardRow, new: CardRow) = old.card.id == new.card.id
        override fun areContentsTheSame(old: CardRow, new: CardRow) = old.card == new.card &&
            (old.onWhy == null) == (new.onWhy == null) && (old.onSave == null) == (new.onSave == null) &&
            (old.onReact == null) == (new.onReact == null)
    }
}

/** Phase 16 / 17E: binds one feed card; shared by every list that shows posts (and Discover's own adapter). */
object CardBinder {

    /** The page and card margins on both sides of a context chip (20 + 16 + 12 dp each side, rounded up). */
    private const val CHIP_SIDE_SPACE_DP = 100

    fun bind(b: ItemExperienceCardBinding, row: CardRow, images: CardImages) {
        val card = row.card
        val context = b.root.context
        images.avatar(b.avatar, card.authorName, card.authorHandle, card.authorId, card.authorPhoto)
        b.name.text = card.authorName ?: context.getString(R.string.feed_someone)
        b.questionBadge.isVisible = card.isAsk

        val photo = card.photo
        b.photoFrame.isVisible = photo != null
        if (photo != null) {
            b.photo.setPhotoSize(photo.width, photo.height)
            b.photo.load(images.photoUrl(photo.mediaId), images.loader) { crossfade(true) } // a video: its poster frame
        } else {
            b.photo.dispose()
            b.photo.setImageDrawable(null)
        }
        val tag = card.category?.let { context.getString(SocialLabels.of(it)) }
        b.categoryTag.isVisible = photo != null && tag != null
        b.categoryTag.text = tag.orEmpty()
        b.meta.text = CardTexts.meta(context, card, categoryShown = photo != null)
        // Phase 17C: a video shows "▶ 0:42" on its still; tapping the card opens the post, where it can be played.
        val length = photo?.takeIf { it.isVideo }?.durationMs?.let { VideoRules.durationLabel(it.toLong()) }
        b.videoBadge.isVisible = length != null
        b.videoBadge.text = length.orEmpty()
        b.mediaCount.isVisible = photo != null && card.mediaCount > 1
        b.mediaCount.text = context.getString(R.string.feed_media_count, card.mediaCount)

        b.thought.isVisible = card.thought != null && photo != null
        b.thought.text = card.thought.orEmpty()
        b.quote.isVisible = card.thought != null && photo == null
        b.quote.text = card.thought.orEmpty()
        b.collapsedLine.isVisible = card.collapse != null
        b.collapsedLine.text = card.collapse?.let { context.getString(CardTexts.collapsedLine(it)) }.orEmpty()
        contextChips(b.contextChips, card, context)

        b.content.setOnClickListener { row.onOpen() }
        b.content.contentDescription = CardTexts.spoken(context, card)

        bindReaction(b.reactButton, card.reacted, card.canReact && row.onReact != null, row.onReact)
        bindSave(b.saveButton, card.saved, card.canSave && row.onSave != null, row.onSave)
        val config = context.resources.configuration
        val fit = MarksFit.mode(config.screenWidthDp, config.fontScale)
        b.commentButton.text = when {
            card.commentCount > 0 -> String.format(Locale.getDefault(), "%d", card.commentCount)
            fit == MarksFit.Mode.FULL -> context.getString(R.string.feed_comment)
            else -> ""
        }
        b.commentButton.contentDescription = CardTexts.commentsSpoken(context, card.commentCount)
        b.commentButton.setOnClickListener { (row.onComments ?: row.onOpen)() }
        b.whyButton.isVisible = row.onWhy != null
        b.whyButton.setOnClickListener { row.onWhy?.invoke() }
        fitMarks(b, fit)
    }

    /** Phase 17E ([MarksFit]): on a narrow screen or with large text the marks lose their words, never their place. */
    private fun fitMarks(b: ItemExperienceCardBinding, fit: MarksFit.Mode) {
        val icons = fit == MarksFit.Mode.ICONS
        val gap = (6 * b.root.resources.displayMetrics.density).toInt()
        if (icons) {
            b.reactButton.text = ""
            b.saveButton.text = ""
        }
        for (button in listOf(b.reactButton, b.commentButton, b.saveButton)) {
            button.iconPadding = if (button.text.isNullOrEmpty()) 0 else gap
        }
        if (icons) {
            b.whyButton.text = ""
            b.whyButton.setIconResource(R.drawable.ic_info)
        } else {
            b.whyButton.setText(R.string.discover_why)
            b.whyButton.icon = null
        }
    }

    /**
     * The butterfly (a reaction): an outline butterfly, or a lime pill with the given butterfly. The pill and the
     * drawing change together, and the spoken words say the state (never colour alone).
     */
    fun bindReaction(button: MaterialButton, reacted: Boolean, visible: Boolean, onReact: (() -> Unit)?) {
        val context = button.context
        button.isVisible = visible
        if (!visible) return
        button.icon = if (reacted) ButterflyDrawable.reacted(context, 24) else ButterflyDrawable.outline(context, 24)
        button.text = context.getString(R.string.reaction_label)
        fill(button, reacted, tintIcon = false)
        button.contentDescription = context.getString(if (reacted) R.string.reaction_given_description
            else R.string.reaction_give_description)
        button.setOnClickListener { onReact?.invoke() }
    }

    /** Save: an outline bookmark ("Save"), or a filled one on a lime pill ("Saved"). */
    fun bindSave(button: MaterialButton, saved: Boolean, visible: Boolean, onSave: (() -> Unit)?) {
        val context = button.context
        button.isVisible = visible
        if (!visible) return
        button.setIconResource(if (saved) R.drawable.ic_bookmark_filled else R.drawable.ic_bookmark)
        button.text = context.getString(if (saved) R.string.saved_label else R.string.save_label)
        fill(button, saved, tintIcon = true)
        button.contentDescription = context.getString(if (saved) R.string.save_remove_description
            else R.string.save_description)
        button.setOnClickListener { onSave?.invoke() }
    }

    /** A given mark fills with lime (ink words and icon on it, both themes); otherwise transparent with ink. */
    private fun fill(button: MaterialButton, on: Boolean, tintIcon: Boolean) {
        val ink = MaterialColors.getColor(button, if (on) R.attr.colorOnLime
            else com.google.android.material.R.attr.colorOnSurface)
        button.backgroundTintList = ColorStateList.valueOf(if (on) MaterialColors.getColor(button, R.attr.colorLime)
            else android.graphics.Color.TRANSPARENT)
        button.setTextColor(ink)
        // The butterfly is drawn in its own colours; a vector icon takes the ink.
        button.iconTint = if (tintIcon) ColorStateList.valueOf(ink) else null
    }

    private fun contextChips(group: ChipGroup, card: ExperienceCard, context: Context) {
        group.removeAllViews()
        CardTexts.placeChip(context, card)?.let { group.addView(chip(context, it, R.drawable.ic_place)) }
        CardTexts.whenLine(context, card)?.let { group.addView(chip(context, it, R.drawable.ic_schedule)) }
        if (card.hasTip) group.addView(chip(context, context.getString(R.string.feed_tip_chip), R.drawable.ic_star))
        group.isVisible = group.childCount > 0
    }

    private fun chip(context: Context, text: String, icon: Int) =
        Chip(context, null, R.attr.contextChipStyle).apply {
            this.text = text
            setChipIconResource(icon)
            isChipIconVisible = true
            // Phase 17E: a long line ("November 2026, Morning" at 2.0x text) ends in "…" instead of being cut off by
            // the card; the full words are in the card's spoken description.
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxWidth = context.resources.displayMetrics.widthPixels -
                (CHIP_SIDE_SPACE_DP * context.resources.displayMetrics.density).toInt()
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
}

/** The words of a card, built from the pure [ExperienceCard] (also used by the post screen). */
object CardTexts {

    /**
     * Under the name: when it was shared (a band; own posts add who can see it), and the category when the photo's tag
     * does not already show it.
     */
    fun meta(context: Context, card: ExperienceCard, categoryShown: Boolean = false): String = listOfNotNull(
        card.category?.takeUnless { categoryShown }?.let { context.getString(SocialLabels.of(it)) },
        context.getString(TimeBandTexts.of(card.band)),
        card.audience?.let { context.getString(ExperienceLabels.of(it)) }
    ).joinToString(" · ")

    /** Where, in plain words and only as exactly as the author chose: a place, "Around <city>", a city, or nothing. */
    fun placeChip(context: Context, card: ExperienceCard): String? = when (val place = card.place) {
        is StampPlace.Exact -> place.name
        is StampPlace.Area -> Cities.cityOf(place.gridRef)?.let {
            context.getString(R.string.feed_area_in_city, it.name)
        } ?: context.getString(R.string.feed_area_unknown)
        is StampPlace.InCity -> if (place.city.isOther) context.getString(R.string.city_other) else place.city.name
        StampPlace.Withheld -> null
    }

    /**
     * Phase 13F: the place name "Going there?" may fill in (a named place or a city). An area is not a destination
     * anyone can look up, so it gives none; the person types one if they want.
     */
    fun goingLabel(card: ExperienceCard): String? = when (val place = card.place) {
        is StampPlace.Exact -> place.name
        is StampPlace.InCity -> place.city.takeUnless { it.isOther }?.name
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

    /** One sentence for TalkBack: who, what kind, their words (or why collapsed), where, when, tip, own marks. */
    fun spoken(context: Context, card: ExperienceCard): String = listOfNotNull(
        card.authorName,
        if (card.isAsk) context.getString(R.string.feed_question_badge) else null,
        meta(context, card),
        card.thought ?: card.collapse?.let { context.getString(collapsedLine(it)) },
        card.photo?.takeIf { it.isVideo }?.durationMs?.let {
            context.getString(R.string.feed_video_spoken, VideoRules.durationLabel(it.toLong()))
        },
        if (card.mediaCount > 1) context.getString(R.string.feed_media_count_spoken, card.mediaCount) else null,
        placeChip(context, card),
        whenLine(context, card),
        if (card.hasTip) context.getString(R.string.feed_tip_chip) else null,
        if (card.reacted) context.getString(R.string.reaction_given_short) else null,
        if (card.saved) context.getString(R.string.saved_label) else null
    ).map { it.trim().trimEnd('.') }.filter { it.isNotEmpty() }.joinToString(". ") // one full stop between parts
}

/** Phase 17E: the card images of this app (the ONE loader; photo and profile-photo addresses). */
fun androidx.fragment.app.Fragment.cardImages(): CardImages {
    val container = appContainer
    return CardImages(container.imageLoader, container.experienceRepository::photoUrl,
        container.experienceRepository::personPhotoUrl)
}
