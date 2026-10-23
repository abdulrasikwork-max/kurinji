package com.safesphere.app.feature.social.data.remote

import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Phase 13B: places, posts, photos and perspectives. Created from the ONE Retrofit instance in AppContainer, so the JWT
 * is attached by AuthInterceptor. Endpoints answering 204 have no return type.
 */
interface ExperienceApi {

    @POST("api/places")
    suspend fun createPlace(@Body body: PlaceRequestDto): PlaceDto

    @GET("api/places/nearby")
    suspend fun nearbyPlaces(@Query("area") areaCell: String): List<PlaceDto>

    /** The body is the JPEG itself (image/jpeg). */
    @POST("api/media")
    suspend fun upload(@Body jpeg: RequestBody): UploadedDto

    @POST("api/posts")
    suspend fun createPost(@Body body: PostRequestDto): PostDto

    @GET("api/posts/{id}")
    suspend fun post(@Path("id") publicId: String): PostDto

    @DELETE("api/posts/{id}")
    suspend fun deletePost(@Path("id") publicId: String)

    @GET("api/posts/mine")
    suspend fun myPosts(@Query("cursor") cursor: String?): PostPageDto

    @GET("api/profiles/{id}/posts")
    suspend fun profilePosts(@Path("id") profileId: String, @Query("cursor") cursor: String?): PostPageDto

    @GET("api/posts/{id}/comments")
    suspend fun comments(@Path("id") postId: String): List<CommentDto>

    @POST("api/posts/{id}/comments")
    suspend fun addComment(@Path("id") postId: String, @Body body: CommentRequestDto): CommentDto

    @DELETE("api/comments/{id}")
    suspend fun deleteComment(@Path("id") publicId: String)

    @POST("api/comments/{id}/hide")
    suspend fun hide(@Path("id") publicId: String)

    @POST("api/comments/{id}/unhide")
    suspend fun unhide(@Path("id") publicId: String)

    @POST("api/comments/{id}/helped")
    suspend fun helped(@Path("id") publicId: String)

    @DELETE("api/comments/{id}/helped")
    suspend fun notHelped(@Path("id") publicId: String)
}
