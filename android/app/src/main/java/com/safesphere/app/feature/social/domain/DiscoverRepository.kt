package com.safesphere.app.feature.social.domain

import com.safesphere.app.core.network.ApiResult

/**
 * Phase 13C: butterflies, the Cocoon and Discover on the backend. Every list ends; more only with the cursor the
 * previous page gave, asked for by an explicit tap. Nothing is cached or stored on the phone.
 */
interface DiscoverRepository {

    /** Saves the post to the Cocoon (WANT) or moves it to [state]; answers the state the server now has. */
    suspend fun setButterfly(postId: String, state: ButterflyState): ApiResult<ButterflyState>

    suspend fun removeButterfly(postId: String): ApiResult<Unit>

    suspend fun cocoon(state: ButterflyState, cursor: String?): ApiResult<CocoonPage>

    suspend fun edition(city: City): ApiResult<Edition>

    suspend fun fromConnections(cursor: String?): ApiResult<DiscoverPage>

    suspend fun categoryPage(category: Interest, city: City?, cursor: String?): ApiResult<PostPage>

    suspend fun placePage(placeId: String, cursor: String?): ApiResult<PlacePage>

    /** Places by name only (no people search). */
    suspend fun searchPlaces(query: String, city: City?): ApiResult<List<Place>>
}
