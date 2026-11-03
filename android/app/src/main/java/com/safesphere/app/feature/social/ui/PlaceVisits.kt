package com.safesphere.app.feature.social.ui

import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.social.domain.DiscoverRepository
import com.safesphere.app.feature.social.domain.Visit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Phase 17G-2 (U5): "I went" and the person's OWN rating for one place, for any screen that shows a place (the place page, a post
 * about it). It keeps only what the server just answered about THIS person's own visit ([onVisit]; null = not marked) and says when
 * "I went" was just confirmed ([onWent], so the screen can ask "How was it?"). One call at a time: a double tap sends nothing twice.
 * A rating outside 1 to 5 is never sent. Nothing is stored on the phone, and nobody else's visit or rating exists here.
 */
class PlaceVisits(
    private val repository: DiscoverRepository,
    private val scope: CoroutineScope,
    private val onVisit: (Visit?) -> Unit,
    private val onWent: () -> Unit,
    private val onBusy: (Boolean) -> Unit,
    private val onFailure: (ApiResult.Failure) -> Unit
) {
    private var busy = false

    /** "I went": mark, then [onWent]. */
    fun went(placeId: String) = run {
        when (val r = repository.went(placeId)) {
            is ApiResult.Success -> {
                onVisit(r.data)
                onWent()
            }
            is ApiResult.Failure -> onFailure(r)
        }
    }

    fun rate(placeId: String, rating: Int) {
        if (!Visit.isValid(rating)) return
        run {
            when (val r = repository.rate(placeId, rating)) {
                is ApiResult.Success -> onVisit(r.data)
                is ApiResult.Failure -> onFailure(r)
            }
        }
    }

    fun clearRating(placeId: String) = run {
        when (val r = repository.clearRating(placeId)) {
            is ApiResult.Success -> onVisit(r.data)
            is ApiResult.Failure -> onFailure(r)
        }
    }

    /** Takes "I went" back (and the rating with it). */
    fun notWent(placeId: String) = run {
        when (val r = repository.notWent(placeId)) {
            is ApiResult.Success -> onVisit(null)
            is ApiResult.Failure -> onFailure(r)
        }
    }

    private fun run(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        onBusy(true)
        scope.launch {
            try {
                block()
            } finally {
                busy = false
                onBusy(false)
            }
        }
    }
}
