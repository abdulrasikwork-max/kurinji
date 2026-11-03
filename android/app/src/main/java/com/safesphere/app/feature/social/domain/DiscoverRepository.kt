package com.safesphere.app.feature.social.domain

import com.safesphere.app.core.network.ApiResult

/**
 * Phase 13C: Discover on the backend; Phase 17E (D-3): the butterfly (a reaction) and Saved (a private bookmark). Every
 * list ends; more only with the cursor the previous page gave. Nothing is cached or stored on the phone. Marking and
 * un-marking are idempotent on the server, so a toggle can be shown at once and put back if the call fails.
 */
interface DiscoverRepository {

    suspend fun react(postId: String): ApiResult<Unit>

    suspend fun unreact(postId: String): ApiResult<Unit>

    suspend fun save(postId: String): ApiResult<Unit>

    suspend fun unsave(postId: String): ApiResult<Unit>

    suspend fun saved(cursor: String?): ApiResult<SavedPage>

    suspend fun edition(city: City): ApiResult<Edition>

    suspend fun fromConnections(cursor: String?): ApiResult<DiscoverPage>

    suspend fun categoryPage(category: Interest, city: City?, cursor: String?): ApiResult<PostPage>

    suspend fun placePage(placeId: String, cursor: String?): ApiResult<PlacePage>

    /**
     * Phase 17G-2 (U5): "I went" for a place the person can see (private to them; repeating it is harmless). A 404 means the
     * place is not one they can see.
     */
    suspend fun went(placeId: String): ApiResult<Visit>

    /** Takes "I went" back, and the rating with it. */
    suspend fun notWent(placeId: String): ApiResult<Unit>

    /** The person's own rating, 1 to 5, for a place they went to. A rating outside 1..5 never leaves the phone. */
    suspend fun rate(placeId: String, rating: Int): ApiResult<Visit>

    /** Takes the rating back and keeps "I went". */
    suspend fun clearRating(placeId: String): ApiResult<Visit>

    /** Places by name only (no people search). */
    suspend fun searchPlaces(query: String, city: City?): ApiResult<List<Place>>
}
