package com.safesphere.app.feature.social.data.remote

import com.safesphere.app.feature.social.domain.Cities
import com.safesphere.app.feature.social.domain.SavedItem
import com.safesphere.app.feature.social.domain.SavedPage
import com.safesphere.app.feature.social.domain.DiscoverItem
import com.safesphere.app.feature.social.domain.DiscoverPage
import com.safesphere.app.feature.social.domain.DiscoverReason
import com.safesphere.app.feature.social.domain.Edition
import com.safesphere.app.feature.social.domain.EditionSection
import com.safesphere.app.feature.social.domain.EditionSectionKind
import com.safesphere.app.feature.social.domain.Interest
import com.safesphere.app.feature.social.domain.PlacePage
import com.safesphere.app.feature.social.domain.TimeBand
import java.time.LocalDate
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/** Phase 13C: Discover; Phase 17E: reactions and Saved. From the ONE Retrofit instance (the JWT is attached). */
interface DiscoverApi {

    // Phase 17E (D-3): idempotent on the server (PUT twice = one row; DELETE of nothing = 204).
    @PUT("api/posts/{id}/reaction")
    suspend fun react(@Path("id") postId: String): MarkResponseDto

    @DELETE("api/posts/{id}/reaction")
    suspend fun unreact(@Path("id") postId: String)

    @PUT("api/posts/{id}/save")
    suspend fun save(@Path("id") postId: String): MarkResponseDto

    @DELETE("api/posts/{id}/save")
    suspend fun unsave(@Path("id") postId: String)

    @GET("api/saved")
    suspend fun saved(@Query("cursor") cursor: String?): SavedPageDto

    @GET("api/discover/edition")
    suspend fun edition(@Query("city") city: String): EditionDto

    @GET("api/discover/connections")
    suspend fun fromConnections(@Query("cursor") cursor: String?): DiscoverPageDto

    @GET("api/discover/categories/{category}")
    suspend fun categoryPage(@Path("category") category: String, @Query("city") city: String?,
                             @Query("cursor") cursor: String?): PostPageDto

    @GET("api/places/{id}/posts")
    suspend fun placePage(@Path("id") placeId: String, @Query("cursor") cursor: String?): PlacePageDto

    @GET("api/places/search")
    suspend fun searchPlaces(@Query("q") query: String, @Query("city") city: String?): List<PlaceDto>
}

// These classes mirror the JSON (docs/API.md "Phase 13C"). They FAIL CLOSED like the 13B ones: an unknown state, kind or
// band is never guessed into something more certain; an item missing what it needs is dropped; unknown reasons vanish.

data class MarkResponseDto(val marked: Boolean? = null)

data class SavedItemDto(val post: PostDto? = null, val savedPeriod: String? = null) {
    fun toDomain(): SavedItem? {
        return SavedItem(
            post = post?.toDomain() ?: return null,
            savedPeriod = enumOrNull<TimeBand>(savedPeriod) ?: TimeBand.EARLIER
        )
    }
}

data class SavedPageDto(val items: List<SavedItemDto>? = null, val nextCursor: String? = null) {
    fun toDomain() = SavedPage(items.orEmpty().mapNotNull { it.toDomain() }, nextCursor)
}

data class DiscoverItemDto(val post: PostDto? = null, val reasons: List<String>? = null) {
    fun toDomain(): DiscoverItem? {
        return DiscoverItem(
            post = post?.toDomain() ?: return null,
            reasons = reasons.orEmpty().mapNotNull { enumOrNull<DiscoverReason>(it) }.distinct()
        )
    }
}

data class EditionSectionDto(val kind: String? = null, val items: List<DiscoverItemDto>? = null) {
    /** An unknown section kind is dropped (the app would not know how to title it). */
    fun toDomain(): EditionSection? {
        return EditionSection(
            kind = enumOrNull<EditionSectionKind>(kind) ?: return null,
            items = items.orEmpty().mapNotNull { it.toDomain() }
        )
    }
}

data class EditionDto(
    val city: String? = null,
    val weekOf: String? = null,
    val theme: String? = null,
    val sections: List<EditionSectionDto>? = null
) {
    fun toDomain(): Edition? {
        return Edition(
            city = Cities.listCity(city) ?: return null,
            weekOf = weekOf?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            theme = theme?.let(Interest::fromCode),
            sections = sections.orEmpty().mapNotNull { it.toDomain() }.filter { it.items.isNotEmpty() }
        )
    }
}

data class DiscoverPageDto(val items: List<DiscoverItemDto>? = null, val nextCursor: String? = null) {
    fun toDomain() = DiscoverPage(items.orEmpty().mapNotNull { it.toDomain() }, nextCursor)
}

data class PlacePageDto(val place: PlaceDto? = null, val items: List<PostDto>? = null, val nextCursor: String? = null) {
    fun toDomain(): PlacePage? {
        return PlacePage(
            place = place?.toDomain() ?: return null,
            items = items.orEmpty().mapNotNull { it.toDomain() },
            nextCursor = nextCursor
        )
    }
}
