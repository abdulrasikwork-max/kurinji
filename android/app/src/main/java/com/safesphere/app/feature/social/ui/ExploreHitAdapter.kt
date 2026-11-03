package com.safesphere.app.feature.social.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.safesphere.app.R
import com.safesphere.app.databinding.ItemExploreHitBinding
import com.safesphere.app.feature.social.domain.ExploreFacets
import com.safesphere.app.feature.social.domain.ExploreHit

/**
 * Phase 17G-1: the words of one Explore answer, in one place so the card and its spoken description say the same. Reasons are
 * codes from the server turned into words here (the Edition pattern). Never a count of people or posts: "3 of 4" counts only
 * the things the person asked for.
 */
object ExploreTexts {

    fun subtitle(context: Context, hit: ExploreHit): String {
        val category = hit.category?.let { context.getString(SocialLabels.of(it)) }
        val city = hit.city?.name
        return listOfNotNull(category, city).joinToString(" · ")
    }

    fun match(context: Context, hit: ExploreHit): String =
        context.resources.getQuantityString(R.plurals.explore_match_of, hit.requested, hit.matched, hit.requested)

    /** "Quiet · Scenic": the tags that matched (empty when only the category or the time of day matched). */
    fun tags(hit: ExploreHit): String =
        hit.matchedFacets.mapNotNull { ExploreFacets.find(it)?.english }.joinToString(" · ")

    fun description(context: Context, hit: ExploreHit): String = listOfNotNull(
        hit.name, subtitle(context, hit).ifBlank { null }, match(context, hit),
        tags(hit).ifBlank { null }?.let { context.getString(R.string.explore_hit_good_for, it) }
    ).joinToString(". ")
}

/** A list of places Explore found; a tap opens the place's page. */
class ExploreHitAdapter(private val onOpen: (ExploreHit) -> Unit) :
    ListAdapter<ExploreHit, ExploreHitAdapter.Holder>(Diff) {

    class Holder(val binding: ItemExploreHitBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemExploreHitBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val hit = getItem(position)
        val context = holder.binding.root.context
        with(holder.binding) {
            exploreHitName.text = hit.name
            exploreHitSubtitle.text = ExploreTexts.subtitle(context, hit)
            exploreHitSubtitle.isVisible = exploreHitSubtitle.text.isNotEmpty()
            exploreHitMatch.text = ExploreTexts.match(context, hit)
            val tags = ExploreTexts.tags(hit)
            exploreHitTags.isVisible = tags.isNotEmpty()
            exploreHitTags.text = context.getString(R.string.explore_hit_good_for, tags)
            root.contentDescription = ExploreTexts.description(context, hit)
            root.setOnClickListener { onOpen(hit) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<ExploreHit>() {
        override fun areItemsTheSame(old: ExploreHit, new: ExploreHit) = old.placeId == new.placeId
        override fun areContentsTheSame(old: ExploreHit, new: ExploreHit) = old == new
    }
}
