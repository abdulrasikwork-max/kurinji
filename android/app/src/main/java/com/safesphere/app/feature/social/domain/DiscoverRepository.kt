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

    /** Places by name only (no people search). */
    suspend fun searchPlaces(query: String, city: City?): ApiResult<List<Place>>
}
