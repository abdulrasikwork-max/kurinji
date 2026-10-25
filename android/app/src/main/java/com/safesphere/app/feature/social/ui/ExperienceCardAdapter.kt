package com.safesphere.app.feature.social.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.ImageLoader
import coil.load
import com.google.android.material.button.MaterialButton
import com.safesphere.app.R
import com.safesphere.app.core.ui.components.ButterflyDrawable
import com.safesphere.app.core.ui.components.PostmarkView
import com.safesphere.app.databinding.ItemAskCardBinding
import com.safesphere.app.databinding.ItemExperienceCardBinding
import com.safesphere.app.feature.social.domain.ButterflyState
import java.util.Locale

/** One card in a list: what it shows, what a tap does, and (Edition items) "Why am I seeing this?". */
data class CardRow(val card: ExperienceCard, val onOpen: () -> Unit, val onWhy: (() -> Unit)? = null)

/**
 * Phase 13F (F13): experiences as postcards and Asks as question slips, in Discover, place and category pages,
 * profiles and "Your experiences". Photos come through the ONE Coil loader (memory cache only, JWT attached).
 * Each card is one TalkBack item with one sentence; "Why?" is also a TalkBack action.
 */
class ExperienceCardAdapter(
    private val loader: ImageLoader,
    private val photoUrl: (String) -> String
) : ListAdapter<CardRow, RecyclerView.ViewHolder>(Diff) {

    class ExperienceHolder(val binding: ItemExperienceCardBinding) : RecyclerView.ViewHolder(binding.root)
    class AskHolder(val binding: ItemAskCardBinding) : RecyclerView.ViewHolder(binding.root)

    override fun getItemViewType(position: Int) = if (getItem(position).card.isAsk) ASK else EXPERIENCE

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == ASK) AskHolder(ItemAskCardBinding.inflate(inflater, parent, false))
        else ExperienceHolder(ItemExperienceCardBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val row = getItem(position)
        when (holder) {
            is ExperienceHolder -> bindExperience(holder.binding, row)
            is AskHolder -> bindAsk(holder.binding, row)
        }
    }

    private fun bindExperience(b: ItemExperienceCardBinding, row: CardRow) {
        val card = row.card
        val context = b.root.context
        b.overline.text = CardTexts.overline(context, card)
        showWords(b.headline, b.excerpt, b.collapsedLine, card, context)
        b.ticket.isVisible = card.tip != null
        b.tip.text = card.tip.orEmpty()
        b.byline.text = CardTexts.byline(context, card)

        val photo = card.photo
        b.photo.isVisible = photo != null
        if (photo != null) {
            b.photo.setPhotoSize(photo.width, photo.height)
            b.photo.load(photoUrl(photo.mediaId), loader) { crossfade(true) }
        } else {
            b.photo.setImageDrawable(null)
        }
        // With a photo the postmark is pressed half onto it, like a stamp on a postcard.
        val stamp = context.resources.getDimension(R.dimen.postmark_size_small)
        b.postmark.translationY = if (photo != null) -stamp * 0.55f else 0f
        b.postmark.content = CardTexts.postmark(context, card)

        b.intentGlyph.isVisible = card.intent != null
        when (card.intent) {
            ButterflyState.WANT -> b.intentGlyph.setImageResource(R.drawable.ic_chrysalis)
            ButterflyState.BEEN -> b.intentGlyph.setImageDrawable(ButterflyDrawable.intent(context))
            null -> b.intentGlyph.setImageDrawable(null)
        }
        bindActions(b.card, b.whyButton, row, context)
    }

    private fun bindAsk(b: ItemAskCardBinding, row: CardRow) {
        val card = row.card
        val context = b.root.context
        b.overline.text = CardTexts.overline(context, card)
        b.headline.isVisible = card.headline != null
        b.headline.text = card.headline.orEmpty()
        b.collapsedLine.isVisible = card.collapse != null
        b.collapsedLine.text = card.collapse?.let { context.getString(CardTexts.collapsedLine(it)) }.orEmpty()
        b.byline.text = listOfNotNull(CardTexts.whereLine(context, card), CardTexts.byline(context, card))
            .joinToString(" · ")
        bindActions(b.card, b.whyButton, row, context)
    }

    private fun showWords(headline: TextView, excerpt: TextView, collapsedLine: TextView, card: ExperienceCard, context: Context) {
        headline.isVisible = card.headline != null
        headline.text = card.headline.orEmpty()
        excerpt.isVisible = card.excerpt != null
        excerpt.text = card.excerpt.orEmpty()
        collapsedLine.isVisible = card.collapse != null
        collapsedLine.text = card.collapse?.let { context.getString(CardTexts.collapsedLine(it)) }.orEmpty()
    }

    private fun bindActions(root: View, why: MaterialButton, row: CardRow, context: Context) {
        root.setOnClickListener { row.onOpen() }
        root.contentDescription = CardTexts.spoken(context, row.card)
        why.isVisible = row.onWhy != null
        why.setOnClickListener { row.onWhy?.invoke() }
        ViewCompat.setAccessibilityDelegate(root, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                    AccessibilityNodeInfoCompat.ACTION_CLICK, context.getString(R.string.card_action_open)))
                if (row.onWhy != null) info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                    R.id.card_action_why, context.getString(R.string.discover_why_title)))
            }

            override fun performAccessibilityAction(host: View, action: Int, args: android.os.Bundle?): Boolean {
                if (action == R.id.card_action_why) {
                    row.onWhy?.invoke()
                    return true
                }
                return super.performAccessibilityAction(host, action, args)
            }
        })
    }

    private object Diff : DiffUtil.ItemCallback<CardRow>() {
        override fun areItemsTheSame(old: CardRow, new: CardRow) = old.card.id == new.card.id
        override fun areContentsTheSame(old: CardRow, new: CardRow) =
            old.card == new.card && (old.onWhy == null) == (new.onWhy == null)
    }

    private companion object {
        const val EXPERIENCE = 0
        const val ASK = 1
    }
}

/** Phase 13F: the words of a card, built from the pure [ExperienceCard] (also used by the post screen). */
object CardTexts {

    fun overline(context: Context, card: ExperienceCard): String = listOfNotNull(
        context.getString(if (card.isAsk) R.string.social_ask_badge else R.string.social_type_experience),
        card.category?.let { context.getString(SocialLabels.of(it)) }
    ).joinToString(" · ")

    fun whereLine(context: Context, card: ExperienceCard): String? = when (val place = card.place) {
        is StampPlace.Exact -> context.getString(R.string.social_place_line, place.name)
        is StampPlace.Area -> context.getString(R.string.card_area_line, place.gridRef.uppercase(Locale.ROOT))
        is StampPlace.InCity -> context.getString(R.string.social_city_line_post, ExperienceLabels.cityName(place.city))
        StampPlace.Withheld -> null
    }

    /**
     * Phase 13F: the place name "Going there?" may fill in (a named place or a city). An area's grid reference is not a
     * destination anyone can look up, so it gives none; the person types one if they want.
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

    fun byline(context: Context, card: ExperienceCard): String = listOfNotNull(
        card.authorName?.takeIf { !card.own },
        context.getString(TimeBandTexts.of(card.band)),
        card.audience?.let { context.getString(ExperienceLabels.of(it)) }
    ).joinToString(" · ")

    fun collapsedLine(collapse: CardCollapse): Int = when (collapse) {
        is CardCollapse.ByModeration -> ModerationLabels.collapsedLine(collapse.collapsed)
        CardCollapse.StrongLanguage -> R.string.collapsed_strong_language
    }

    /** The postmark: the place on the ring, the month (or "?" for an Ask) in the middle, year and time of day below. */
    fun postmark(context: Context, card: ExperienceCard): PostmarkView.Content =
        postmark(context, card.isAsk, card.place, card.month, card.daypart, card.band)

    /** Also used live in the composer, before anything is shared. */
    fun postmark(
        context: Context,
        isAsk: Boolean,
        place: StampPlace,
        month: java.time.YearMonth?,
        daypart: com.safesphere.app.feature.social.domain.Daypart?,
        band: com.safesphere.app.feature.social.domain.TimeBand
    ): PostmarkView.Content {
        val card = PostmarkFacts(isAsk, place, month, daypart, band)
        val ring = when (val place = card.place) {
            is StampPlace.Exact -> place.name
            is StampPlace.Area -> context.getString(R.string.card_stamp_grid, place.gridRef.uppercase(Locale.ROOT))
            is StampPlace.InCity -> ExperienceLabels.cityName(place.city)
            StampPlace.Withheld -> ""
        }
        val daypart = card.daypart?.let { context.getString(ExperienceLabels.of(it)) }
        val month = card.month
        val centre = when {
            card.isAsk -> context.getString(R.string.card_ask_mark)
            month != null -> month.month.getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault())
            else -> null
        }
        val lower = listOfNotNull(month?.year?.toString(), daypart)
            .ifEmpty { listOf(context.getString(TimeBandTexts.of(card.band))) }.joinToString(" · ")
        return PostmarkView.Content(ring = ring, lower = lower, centre = centre, blank = card.place == StampPlace.Withheld)
    }

    private data class PostmarkFacts(
        val isAsk: Boolean,
        val place: StampPlace,
        val month: java.time.YearMonth?,
        val daypart: com.safesphere.app.feature.social.domain.Daypart?,
        val band: com.safesphere.app.feature.social.domain.TimeBand
    )

    /** One sentence for TalkBack: type, headline (or why it is collapsed), where, when, by whom, the band, own state. */
    fun spoken(context: Context, card: ExperienceCard): String = listOfNotNull(
        overline(context, card),
        card.headline ?: card.collapse?.let { context.getString(collapsedLine(it)) },
        whereLine(context, card),
        whenLine(context, card),
        card.tip?.let { context.getString(R.string.card_spoken_tip, it) },
        byline(context, card),
        when (card.intent) {
            ButterflyState.WANT -> context.getString(R.string.butterfly_saved_want)
            ButterflyState.BEEN -> context.getString(R.string.butterfly_saved_been)
            null -> null
        }
    ).map { it.trim().trimEnd('.') }.filter { it.isNotEmpty() }.joinToString(". ") // one full stop between parts
}
