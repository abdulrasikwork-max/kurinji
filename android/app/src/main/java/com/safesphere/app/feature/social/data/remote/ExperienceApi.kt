package com.safesphere.app.feature.social.data.remote

import com.safesphere.app.core.network.UploadTimeoutInterceptor
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming
import retrofit2.http.Url

/**
 * Phase 13B: places, posts, photos and perspectives. Created from the ONE Retrofit instance in AppContainer, so the JWT
 * is attached by AuthInterceptor. Endpoints answering 204 have no return type.
 */
interface ExperienceApi {

    @POST("api/places")
    suspend fun createPlace(@Body body: PlaceRequestDto): PlaceDto

    /** Phase 17E: one place ("I went" fills it into the composer). */
    @GET("api/places/{id}")
    suspend fun place(@Path("id") placeId: String): PlaceDto

    @GET("api/places/nearby")
    suspend fun nearbyPlaces(@Query("area") areaCell: String): List<PlaceDto>

    /** The body is the JPEG itself (image/jpeg); Phase 17C: or the MP4 itself (video/mp4). */
    @Headers(UploadTimeoutInterceptor.HEADER_LINE) // 120 s read / write (UploadTimeoutInterceptor)
    @POST("api/media")
    suspend fun upload(@Body jpeg: RequestBody): UploadedDto

    /** Phase 17C: a video's poster frame (image/jpeg). */
    @Headers(UploadTimeoutInterceptor.HEADER_LINE)
    @POST("api/media/{id}/poster")
    suspend fun uploadPoster(@Path("id") mediaId: String, @Body jpeg: RequestBody): UploadedDto

    /** Phase 17C: an own upload no post uses (204). */
    @DELETE("api/media/{id}")
    suspend fun deleteMedia(@Path("id") mediaId: String)

    /** Phase 17C: the first byte of a video (206), to check it can be played before the player opens it. */
    @Streaming
    @GET
    suspend fun firstByte(@Url url: String, @Header("Range") range: String = "bytes=0-0"): ResponseBody


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

    /** Phase 13D (Q6). */
    @POST("api/comments/{id}/approve")
    suspend fun approve(@Path("id") publicId: String)

    @POST("api/comments/{id}/helped")
    suspend fun helped(@Path("id") publicId: String)

    @DELETE("api/comments/{id}/helped")
    suspend fun notHelped(@Path("id") publicId: String)
}
