package com.safesphere.app.feature.social.domain

import com.safesphere.app.core.network.ApiResult

/**
 * Phase 13B: places, posts, photos and perspectives on the backend. Nothing about other people is stored on the phone;
 * only cells (7 characters to add a place, 6 to list places nearby) ever leave it, never a coordinate.
 */
interface ExperienceRepository {

    suspend fun createPlace(name: String, category: Interest, cell: String): ApiResult<Place>

    suspend fun nearbyPlaces(areaCell: String): ApiResult<List<Place>>

    /**
     * Uploads a JPEG the phone already re-encoded (PhotoPreparer); the server re-encodes it again. Phase 17C:
     * [onProgress] hears the share of the body sent so far (0..1; from 0 again if the call is sent once more).
     */
    suspend fun uploadPhoto(jpeg: ByteArray, onProgress: (Float) -> Unit = {}): ApiResult<UploadedPhoto>

    /** Phase 17C: uploads a video the phone re-packed (VideoPreparer); the server checks and strips it again. */
    suspend fun uploadVideo(mp4: java.io.File, onProgress: (Float) -> Unit = {}): ApiResult<UploadedPhoto>

    /** Phase 17C: the poster frame of the person's own video that no post uses yet (sent again: replaced). */
    suspend fun uploadPoster(mediaId: String, jpeg: ByteArray): ApiResult<UploadedPhoto>

    /** Phase 17C: removes the person's own upload that no post uses (Remove in the composer). */
    suspend fun deleteUpload(mediaId: String): ApiResult<Unit>

    /**
     * Phase 17C: asks for the first byte of a video before the player opens it, through the shared client: a lapsed
     * token is renewed on the way, and "not found" / "no connection" get the app's own words instead of a player error.
     */
    suspend fun checkVideo(url: String): ApiResult<Unit>

    suspend fun createPost(draft: PostDraft): ApiResult<Post>

    suspend fun post(publicId: String): ApiResult<Post>

    suspend fun deletePost(publicId: String): ApiResult<Unit>

    suspend fun myPosts(cursor: String?): ApiResult<PostPage>

    suspend fun profilePosts(profileId: String, cursor: String?): ApiResult<PostPage>

    suspend fun perspectives(postId: String): ApiResult<List<Perspective>>

    suspend fun addPerspective(postId: String, kind: CommentKind, body: String, parentId: String?): ApiResult<Perspective>

    suspend fun deletePerspective(publicId: String): ApiResult<Unit>

    suspend fun setHidden(publicId: String, hidden: Boolean): ApiResult<Unit>

    suspend fun setHelped(publicId: String, helped: Boolean): ApiResult<Unit>

    /** Phase 13D (Q6): the post's author lets a waiting perspective appear. */
    suspend fun approvePerspective(publicId: String): ApiResult<Unit>

    /** The address the photo (Phase 17C: or a video's poster frame) is loaded from (Coil, shared client: JWT attached). */
    fun photoUrl(mediaId: String): String

    /** Phase 17C: the address a video is streamed from (the player sends the JWT itself; nothing is saved). */
    fun videoUrl(mediaId: String): String

}
