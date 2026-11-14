package com.safesphere.app.feature.social.ui

import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.social.domain.DiscoverRepository
import com.safesphere.app.feature.social.domain.Visit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
    private val onFailure: (ApiResult.Failure) -> Unit,
    /**
     * Phase 18A-2: answers the phone's cell if it is AT the place right now, else null (PresenceCell: never asks for a
     * permission, never uses a rough position, null when the phone is anywhere else). Null = this screen never looks.
     */
    private val cellIfAt: (suspend (placeId: String) -> String?)? = null
) {
    private var busy = false
    private var presence: Job? = null

    /** "I went": mark, then [onWent]. */
    fun went(placeId: String) = run {
        when (val r = repository.went(placeId)) {
            is ApiResult.Success -> {
                onVisit(r.data)
                onWent()
                confirmPresence(placeId)
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
        stopLooking() // a late "I am here" must not bring the visit back
        when (val r = repository.notWent(placeId)) {
            is ApiResult.Success -> onVisit(null)
            is ApiResult.Failure -> onFailure(r)
        }
    }

    /**
     * Phase 18A-2: after "Been here" was saved, quietly: if the phone finds it is at the place, the same mark is sent once more
     * with its cell. The person never waits for it, sees nothing of it, and its answer and any failure are ignored (the visit
     * is already saved). Not sent at all when the phone is elsewhere or has no good position.
     */
    private fun confirmPresence(placeId: String) {
        val look = cellIfAt ?: return
        presence?.cancel()
        presence = scope.launch {
            val cell = look(placeId) ?: return@launch
            repository.wentAt(placeId, cell)
        }
    }

    /** The screen is no longer visible, or the visit was taken back: stop looking for a position. */
    fun stopLooking() {
        presence?.cancel()
        presence = null
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
