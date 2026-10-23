package com.safesphere.app.feature.social.domain

import com.safesphere.app.core.network.ApiResult

/**
 * Phase 13B: places, posts, photos and perspectives on the backend. Nothing about other people is stored on the phone;
 * only cells (7 characters to add a place, 6 to list places nearby) ever leave it, never a coordinate.
 */
interface ExperienceRepository {

    suspend fun createPlace(name: String, category: Interest, cell: String): ApiResult<Place>

    suspend fun nearbyPlaces(areaCell: String): ApiResult<List<Place>>

    /** Uploads a JPEG the phone already re-encoded (PhotoPreparer); the server re-encodes it again. */
    suspend fun uploadPhoto(jpeg: ByteArray): ApiResult<UploadedPhoto>

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

    /** The address the photo is loaded from (Coil, through the shared client so the JWT is attached). */
    fun photoUrl(mediaId: String): String
}
