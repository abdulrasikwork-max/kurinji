package com.safesphere.app.feature.social.ui

import android.content.Context
import com.safesphere.app.R
import com.safesphere.app.feature.social.domain.Post
import com.safesphere.app.feature.social.domain.PostType
import com.safesphere.app.feature.social.domain.Precision

/** Phase 13B: a post as one row of a list (on a profile and in "Your experiences"): its words, where, and a band. */
object PostRows {

    fun of(context: Context, post: Post, open: () -> Unit): PersonRow {
        val firstLine = post.body.lineSequence().firstOrNull().orEmpty().let { if (it.length > 80) it.take(79) + "…" else it }
        val where = when (post.precision) {
            Precision.EXACT -> post.placeName?.let { context.getString(R.string.social_place_line, it) }
            Precision.AREA -> post.areaCell?.let { context.getString(R.string.social_area_line, it) }
            Precision.CITY -> post.city?.let { context.getString(R.string.social_city_line_post, ExperienceLabels.cityName(it)) }
            Precision.NONE -> null
        }
        val type = context.getString(if (post.type == PostType.ASK) R.string.social_ask_badge else R.string.social_type_experience)
        val details = listOfNotNull(type, where, context.getString(TimeBandTexts.of(post.postedPeriod)),
            post.audience?.let { context.getString(ExperienceLabels.of(it)) })
        return PersonRow(
            key = post.publicId,
            title = firstLine,
            subtitle = details.joinToString(" · "),
            onOpen = open,
            openDescription = context.getString(R.string.social_open_post) + ": " + firstLine
        )
    }
}
