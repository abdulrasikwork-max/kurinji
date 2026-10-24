package com.safesphere.app.feature.social.data

import com.safesphere.app.core.network.ApiCallHandler
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.social.data.remote.CommentRequestDto
import com.safesphere.app.feature.social.data.remote.ExperienceApi
import com.safesphere.app.feature.social.data.remote.PlaceRequestDto
import com.safesphere.app.feature.social.data.remote.PostRequestDto
import com.safesphere.app.feature.social.domain.CommentKind
import com.safesphere.app.feature.social.domain.ExperienceRepository
import com.safesphere.app.feature.social.domain.Interest
import com.safesphere.app.feature.social.domain.Perspective
import com.safesphere.app.feature.social.domain.Place
import com.safesphere.app.feature.social.domain.Post
import com.safesphere.app.feature.social.domain.PostDraft
import com.safesphere.app.feature.social.domain.PostPage
import com.safesphere.app.feature.social.domain.UploadedPhoto
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Phase 13B: experiences on the backend. All error handling is in [ApiCallHandler]. Nothing is cached. An answer the
 * app cannot read (a DTO that fails closed to null) is treated as "not found".
 */
class RemoteExperienceRepository(
    private val api: ExperienceApi,
    private val handler: ApiCallHandler,
    private val baseUrl: String
) : ExperienceRepository {

    override suspend fun createPlace(name: String, category: Interest, cell: String): ApiResult<Place> =
        orNotFound(handler.execute { api.createPlace(PlaceRequestDto(name.trim(), category.name, cell)).toDomain() })

    override suspend fun nearbyPlaces(areaCell: String): ApiResult<List<Place>> =
        handler.execute { api.nearbyPlaces(areaCell).mapNotNull { it.toDomain() } }

    override suspend fun uploadPhoto(jpeg: ByteArray): ApiResult<UploadedPhoto> =
        orNotFound(handler.execute { api.upload(jpeg.toRequestBody(JPEG)).toDomain() })

    override suspend fun createPost(draft: PostDraft): ApiResult<Post> =
        orNotFound(handler.execute { api.createPost(PostRequestDto.from(draft)).toDomain() })

    override suspend fun post(publicId: String): ApiResult<Post> = orNotFound(handler.execute { api.post(publicId).toDomain() })

    override suspend fun deletePost(publicId: String): ApiResult<Unit> = handler.execute { api.deletePost(publicId) }

    override suspend fun myPosts(cursor: String?): ApiResult<PostPage> = handler.execute { api.myPosts(cursor).toDomain() }

    override suspend fun profilePosts(profileId: String, cursor: String?): ApiResult<PostPage> =
        handler.execute { api.profilePosts(profileId, cursor).toDomain() }

    override suspend fun perspectives(postId: String): ApiResult<List<Perspective>> =
        handler.execute { api.comments(postId).mapNotNull { it.toDomain() } }

    override suspend fun addPerspective(postId: String, kind: CommentKind, body: String, parentId: String?): ApiResult<Perspective> =
        orNotFound(handler.execute { api.addComment(postId, CommentRequestDto(kind.name, body.trim(), parentId)).toDomain() })

    override suspend fun deletePerspective(publicId: String): ApiResult<Unit> = handler.execute { api.deleteComment(publicId) }

    override suspend fun setHidden(publicId: String, hidden: Boolean): ApiResult<Unit> =
        handler.execute { if (hidden) api.hide(publicId) else api.unhide(publicId) }

    override suspend fun setHelped(publicId: String, helped: Boolean): ApiResult<Unit> =
        handler.execute { if (helped) api.helped(publicId) else api.notHelped(publicId) }

    override suspend fun approvePerspective(publicId: String): ApiResult<Unit> = handler.execute { api.approve(publicId) }

    override fun photoUrl(mediaId: String): String = baseUrl.trimEnd('/') + "/api/media/" + mediaId

    private fun <T : Any> orNotFound(result: ApiResult<T?>): ApiResult<T> = when (result) {
        is ApiResult.Success -> result.data?.let { ApiResult.Success(it) } ?: ApiResult.Failure(ApiError.NOT_FOUND)
        is ApiResult.Failure -> result
    }

    private companion object {
        val JPEG = "image/jpeg".toMediaType()
    }
}
