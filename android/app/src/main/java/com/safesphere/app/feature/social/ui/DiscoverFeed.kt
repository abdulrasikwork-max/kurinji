package com.safesphere.app.feature.social.ui

import com.safesphere.app.feature.social.domain.City
import com.safesphere.app.feature.social.domain.DiscoverItem
import com.safesphere.app.feature.social.domain.EditionSectionKind
import com.safesphere.app.feature.social.domain.Interest
import com.safesphere.app.feature.social.domain.PostType

/** One row of the Discover feed. */
sealed interface FeedItem {
    /** "Near me" and the categories, as pills. */
    data object Shortcuts : FeedItem

    data class Header(val kind: HeaderKind, val city: City? = null, val theme: Interest? = null) : FeedItem

    /** "Talking now": a sideways row of posts with recent comments (U2). */
    data class Talking(val items: List<DiscoverItem>) : FeedItem

    data class PostRow(val item: DiscoverItem) : FeedItem

    /** No posts from people you know: an invitation to find them. */
    data object ConnectPrompt : FeedItem

    /** The person has not chosen a city yet (the city pills are open above). */
    data object ChooseCity : FeedItem

    data object Loading : FeedItem

    /** U3: the feed always ends. */
    data object End : FeedItem

    data object Empty : FeedItem
}

enum class HeaderKind { TALKING, CITY, THEME, CONNECTIONS }

/**
 * Phase 16 (docs/PHASE16.md): Discover as ONE feed, built without Android so it can be tested. Order: shortcuts,
 * "Talking now", new in the city (a question after every [ASK_EVERY] posts), the theme of the week, posts from people
 * you know, then a loading row or the end. A post appears once in the main feed (the "Talking now" row may repeat it).
 */
object DiscoverFeed {

    const val ASK_EVERY = 3

    fun build(state: DiscoverUiState): List<FeedItem> {
        val out = mutableListOf<FeedItem>(FeedItem.Shortcuts)
        val edition = state.edition
        val seen = HashSet<String>()
        var posts = 0

        fun add(item: DiscoverItem) {
            if (seen.add(item.post.publicId)) {
                out += FeedItem.PostRow(item)
                posts++
            }
        }

        if (state.city == null && state.needsCity) out += FeedItem.ChooseCity
        if (edition != null) {
            fun section(kind: EditionSectionKind) = edition.sections.firstOrNull { it.kind == kind }?.items.orEmpty()
            val talking = section(EditionSectionKind.TALKING)
            if (talking.isNotEmpty()) {
                out += FeedItem.Header(HeaderKind.TALKING)
                out += FeedItem.Talking(talking)
            }
            val city = section(EditionSectionKind.CITY)
            val asks = section(EditionSectionKind.ASKS).filter { it.post.type == PostType.ASK }
            if (city.isNotEmpty() || asks.isNotEmpty()) {
                out += FeedItem.Header(HeaderKind.CITY, city = edition.city)
                var nextAsk = 0
                city.forEachIndexed { index, item ->
                    add(item)
                    if ((index + 1) % ASK_EVERY == 0 && nextAsk < asks.size) add(asks[nextAsk++])
                }
                while (nextAsk < asks.size) add(asks[nextAsk++])
            }
            val theme = section(EditionSectionKind.THEME).filter { it.post.publicId !in seen }
            if (theme.isNotEmpty()) {
                out += FeedItem.Header(HeaderKind.THEME, theme = edition.theme)
                theme.forEach(::add)
            }
        }

        val connections = state.connections.filter { it.post.publicId !in seen }
        if (connections.isNotEmpty()) {
            out += FeedItem.Header(HeaderKind.CONNECTIONS)
            connections.forEach(::add)
        } else if (state.connectionsLoaded && !state.connectionsHasMore && state.connections.isEmpty()) {
            // Only when people you know really shared nothing: their posts may all be in the city list above already
            // (emulator finding: the prompt asked a connected person to "find people").
            out += FeedItem.ConnectPrompt
        }

        val stillLoading = state.editionLoading || state.connectionsLoading || state.connectionsHasMore ||
            (state.city != null && edition == null && state.failure == null)
        val footer = when {
            stillLoading -> FeedItem.Loading
            posts > 0 -> FeedItem.End
            // Nothing to say yet: the city pills (no city) or the error notice (a failure) speak instead.
            state.city == null || state.failure != null -> null
            else -> FeedItem.Empty
        }
        if (footer != null) out += footer
        return out
    }
}
