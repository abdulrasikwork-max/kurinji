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

    // ---- Phase 17C: photos and videos ----
    var photoResults: ArrayDeque<ApiResult<UploadedPhoto>> = ArrayDeque()
    var videoResults: ArrayDeque<ApiResult<UploadedPhoto>> = ArrayDeque()
    var posterResults: ArrayDeque<ApiResult<UploadedPhoto>> = ArrayDeque()
    /** When set, the video upload waits for it (to test Remove during an upload). */
    var videoGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    val uploadedPhotos = mutableListOf<ByteArray>()
    val uploadedVideos = mutableListOf<java.io.File>()
    val posters = mutableListOf<Pair<String, ByteArray>>()
    val deletedUploads = mutableListOf<String>()
    val checkedVideos = mutableListOf<String>()
    var checkVideoResult: ApiResult<Unit> = ApiResult.Success(Unit)
    private var nextMedia = 1

    override suspend fun uploadPhoto(jpeg: ByteArray, onProgress: (Float) -> Unit): ApiResult<UploadedPhoto> {
        uploadedPhotos += jpeg
        onProgress(0.5f)
        onProgress(1f)
        return photoResults.removeFirstOrNull() ?: ApiResult.Success(UploadedPhoto("m${nextMedia++}", 10, 10))
    }

    override suspend fun uploadVideo(mp4: java.io.File, onProgress: (Float) -> Unit): ApiResult<UploadedPhoto> {
        uploadedVideos += mp4
        onProgress(0.25f)
        videoGate?.await()
        return videoResults.removeFirstOrNull() ?: ApiResult.Success(UploadedPhoto("v${nextMedia++}", 640, 360))
    }

    override suspend fun uploadPoster(mediaId: String, jpeg: ByteArray): ApiResult<UploadedPhoto> {
        posters += mediaId to jpeg
        return posterResults.removeFirstOrNull() ?: ApiResult.Success(UploadedPhoto(mediaId, 640, 360))
    }

    override suspend fun deleteUpload(mediaId: String): ApiResult<Unit> {
        deletedUploads += mediaId
        return ApiResult.Success(Unit)
    }

    override suspend fun checkVideo(url: String): ApiResult<Unit> {
        checkedVideos += url
        return checkVideoResult
    }

    override fun videoUrl(mediaId: String) = "http://test/api/media/$mediaId/video"


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

/** Phase 17C: a picked item for tests (the app wraps picker addresses instead). */
data class TestPick(
    override val kind: com.safesphere.app.feature.social.domain.MediaKind,
    override val preview: Any? = null
) : com.safesphere.app.feature.social.data.MediaPick

/** Phase 17C: prepares picked items without the platform; records what was discarded. */
class FakeMediaSource : com.safesphere.app.feature.social.data.MediaSource {
    var photo: ByteArray? = byteArrayOf(1, 2, 3)
    var videoOutcome: com.safesphere.app.feature.social.data.VideoOutcome =
        com.safesphere.app.feature.social.data.VideoOutcome.Ready(video())
    var preparedVideos = 0
    val discarded = mutableListOf<com.safesphere.app.feature.social.data.PreparedVideo>()

    override suspend fun preparePhoto(pick: com.safesphere.app.feature.social.data.MediaPick): ByteArray? = photo

    override suspend fun prepareVideo(pick: com.safesphere.app.feature.social.data.MediaPick):
        com.safesphere.app.feature.social.data.VideoOutcome {
        preparedVideos++
        return videoOutcome
    }

    override fun discard(video: com.safesphere.app.feature.social.data.PreparedVideo) {
        discarded += video
    }

    companion object {
        fun video(name: String = "clip") = com.safesphere.app.feature.social.data.PreparedVideo(
            java.io.File("$name.mp4"), byteArrayOf(9, 9), java.io.File("$name.jpg"), 42_000, 640, 360)
    }
}
