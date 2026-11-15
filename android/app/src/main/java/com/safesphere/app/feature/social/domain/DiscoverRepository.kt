package com.safesphere.app.feature.social.domain

import com.safesphere.app.core.network.ApiResult

/**
 * Phase 13C: Discover on the backend. Every list ends; more only with the cursor the previous page gave. Nothing is
 * cached or stored on the phone. (Reactions, Saved, visits and ratings left in N1.)
 */
interface DiscoverRepository {

    suspend fun edition(city: City): ApiResult<Edition>

    suspend fun fromConnections(cursor: String?): ApiResult<DiscoverPage>

    suspend fun categoryPage(category: Interest, city: City?, cursor: String?): ApiResult<PostPage>

    suspend fun placePage(placeId: String, cursor: String?): ApiResult<PlacePage>

    /** Places by name only (no people search). */
    suspend fun searchPlaces(query: String, city: City?): ApiResult<List<Place>>
}
