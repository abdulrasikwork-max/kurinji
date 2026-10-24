package com.safesphere.app.testing

import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.social.data.PlacesData
import com.safesphere.app.feature.social.data.PlacesLoad
import com.safesphere.app.feature.social.data.PlacesStore
import com.safesphere.app.feature.social.domain.Audience
import com.safesphere.app.feature.social.domain.CommentKind
import com.safesphere.app.feature.social.domain.CommentPolicy
import com.safesphere.app.feature.social.domain.ExperienceRepository
import com.safesphere.app.feature.social.domain.Interest
import com.safesphere.app.feature.social.domain.PersonSummary
import com.safesphere.app.feature.social.domain.Perspective
import com.safesphere.app.feature.social.domain.Place
import com.safesphere.app.feature.social.domain.Post
import com.safesphere.app.feature.social.domain.PostDraft
import com.safesphere.app.feature.social.domain.PostPage
import com.safesphere.app.feature.social.domain.PostType
import com.safesphere.app.feature.social.domain.Precision
import com.safesphere.app.feature.social.domain.TimeBand
import com.safesphere.app.feature.social.domain.UploadedPhoto

/** Test double (Phase 13B): an in-memory experience server that records what the phone sent. */
class FakeExperienceRepository : ExperienceRepository {

    val createdPlaces = mutableListOf<Triple<String, Interest, String>>()
    val nearbyAreas = mutableListOf<String>()
    val drafts = mutableListOf<PostDraft>()
    val sentPerspectives = mutableListOf<Triple<CommentKind, String, String?>>()
    val hidden = mutableListOf<Pair<String, Boolean>>()
    val helped = mutableListOf<Pair<String, Boolean>>()
    val deletedPosts = mutableListOf<String>()

    var nearby: List<Place> = emptyList()
    var createPostResult: ApiResult<Post>? = null
    var postResult: ApiResult<Post> = ApiResult.Success(post())
    var perspectives: List<Perspective> = emptyList()
    var pages: Map<String?, PostPage> = mapOf(null to PostPage(emptyList(), null))
    val pageRequests = mutableListOf<Pair<String?, String?>>()

    override suspend fun createPlace(name: String, category: Interest, cell: String): ApiResult<Place> {
        createdPlaces += Triple(name, category, cell)
        return ApiResult.Success(place("new-place", name, cell))
    }

    override suspend fun nearbyPlaces(areaCell: String): ApiResult<List<Place>> {
        nearbyAreas += areaCell
        return ApiResult.Success(nearby)
    }

    override suspend fun uploadPhoto(jpeg: ByteArray): ApiResult<UploadedPhoto> = ApiResult.Success(UploadedPhoto("m1", 10, 10))

    override suspend fun createPost(draft: PostDraft): ApiResult<Post> {
        drafts += draft
        return createPostResult ?: ApiResult.Success(post(id = "new-post", own = true))
    }

    override suspend fun post(publicId: String): ApiResult<Post> = postResult

    override suspend fun deletePost(publicId: String): ApiResult<Unit> {
        deletedPosts += publicId
        return ApiResult.Success(Unit)
    }

    override suspend fun myPosts(cursor: String?): ApiResult<PostPage> = page(null, cursor)

    override suspend fun profilePosts(profileId: String, cursor: String?): ApiResult<PostPage> = page(profileId, cursor)

    private fun page(profileId: String?, cursor: String?): ApiResult<PostPage> {
        pageRequests += profileId to cursor
        return pages[cursor]?.let { ApiResult.Success(it) } ?: ApiResult.Failure(ApiError.NOT_FOUND)
    }

    override suspend fun perspectives(postId: String): ApiResult<List<Perspective>> = ApiResult.Success(perspectives)

    override suspend fun addPerspective(postId: String, kind: CommentKind, body: String, parentId: String?): ApiResult<Perspective> {
        sentPerspectives += Triple(kind, body, parentId)
        return ApiResult.Success(perspective("p-new", kind))
    }

    override suspend fun deletePerspective(publicId: String): ApiResult<Unit> = ApiResult.Success(Unit)

    override suspend fun setHidden(publicId: String, hidden: Boolean): ApiResult<Unit> {
        this.hidden += publicId to hidden
        return ApiResult.Success(Unit)
    }

    override suspend fun setHelped(publicId: String, helped: Boolean): ApiResult<Unit> {
        this.helped += publicId to helped
        return ApiResult.Success(Unit)
    }

    val approved = mutableListOf<String>()

    override suspend fun approvePerspective(publicId: String): ApiResult<Unit> {
        approved += publicId
        return ApiResult.Success(Unit)
    }

    override fun photoUrl(mediaId: String) = "http://test/api/media/$mediaId"

    companion object {
        fun place(id: String = "place-1", name: String = "Marina Beach", cell: String = "tf2c1xz") =
            Place(id, name, Interest.NATURE_OUTDOORS, cell, cell.take(6))

        fun post(id: String = "post-1", own: Boolean = false, type: PostType = PostType.EXPERIENCE, canComment: Boolean = true) = Post(
            publicId = id, type = type, author = PersonSummary("author-1", "asha", "Asha"), own = own, body = "Went early.",
            tip = null, category = null, precision = Precision.NONE, placeName = null, areaCell = null, city = null,
            experiencedMonth = null, daypart = null, postedPeriod = TimeBand.THIS_WEEK, edited = false, media = emptyList(),
            audience = if (own) Audience.EVERYONE else null, commentPolicy = CommentPolicy.EVERYONE, canComment = canComment,
            visibleFrom = null
        )

        fun perspective(id: String, kind: CommentKind = CommentKind.BEEN_HERE, parentId: String? = null) = Perspective(
            id, kind, PersonSummary("bala-1", "bala", "Bala"), false, "Me too.", TimeBand.THIS_WEEK, false, false, parentId,
            false, false
        )
    }
}

/** Test double (Phase 13B): the encrypted places file, in memory. */
class FakePlacesStore(var load: PlacesLoad = PlacesLoad.Empty) : PlacesStore {
    val saved = mutableListOf<Pair<Long, PlacesData>>()
    var cleared = 0

    override suspend fun load(userId: Long): PlacesLoad = load

    override suspend fun save(userId: Long, data: PlacesData) {
        saved += userId to data
        load = PlacesLoad.Loaded(data)
    }

    override suspend fun clearAll() {
        cleared++
        load = PlacesLoad.Empty
    }
}
