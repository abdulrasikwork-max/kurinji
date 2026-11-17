package com.safesphere.app.testing

import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.moments.domain.DayPart
import com.safesphere.app.feature.moments.domain.Moment
import com.safesphere.app.feature.moments.domain.MomentAudience
import com.safesphere.app.feature.moments.domain.MomentAuthor
import com.safesphere.app.feature.moments.domain.MomentKind
import com.safesphere.app.feature.moments.domain.MomentNotice
import com.safesphere.app.feature.moments.domain.MomentPhoto
import com.safesphere.app.feature.moments.domain.MomentPlan
import com.safesphere.app.feature.moments.domain.MomentRepository
import com.safesphere.app.feature.moments.domain.MomentStatus
import com.safesphere.app.feature.moments.domain.MyMoments
import com.safesphere.app.feature.moments.domain.People
import com.safesphere.app.feature.moments.domain.ProfileMoments
import com.safesphere.app.feature.moments.domain.RoughDay
import com.safesphere.app.feature.moments.domain.RoughTime
import com.safesphere.app.feature.moments.domain.TodayPage
import com.safesphere.app.feature.social.data.MediaPick
import com.safesphere.app.feature.social.data.MediaSource

/** A photo "picked" in a test. */
class TestPick : MediaPick

class FakeMediaSource : MediaSource {
    var photo: ByteArray? = byteArrayOf(1, 2, 3)
    var avatar: ByteArray? = byteArrayOf(9, 9)
    override suspend fun preparePhoto(pick: MediaPick): ByteArray? = photo
    override suspend fun prepareAvatar(pick: MediaPick): ByteArray? = avatar
}

/** A moment as the server would describe it; every part can be replaced. */
fun moment(
    id: String,
    name: String = "Asha Raman",
    mine: Boolean = false,
    text: String? = "Canteen at four?",
    kind: MomentKind = MomentKind.SAY,
    audience: MomentAudience = MomentAudience.COMMUNITY,
    status: MomentStatus = MomentStatus.LIVE,
    kept: Boolean = false,
    collapsed: Boolean = false,
    notice: MomentNotice? = null,
    photo: MomentPhoto? = null,
    plan: MomentPlan? = null
) = Moment(
    id = id, kind = kind, audience = audience, author = MomentAuthor(null, null, name, null), mine = mine, text = text,
    photo = photo, plan = plan, question = null,
    shared = if (status == MomentStatus.WAITING) null else RoughTime(RoughDay.TODAY, DayPart.MORNING), status = status,
    kept = kept, collapsed = collapsed, notice = notice, supportSuggested = false, visibleAt = null, expiresAt = null
)

/** Moments in memory. Today answers the queued pages in order (a page per call), then an empty last page. */
class FakeMomentRepository : MomentRepository {

    val todayCalls = ArrayList<Pair<People, String?>>()
    val pages = ArrayDeque<ApiResult<TodayPage>>()

    var momentResult: ApiResult<Moment> = ApiResult.Failure(ApiError.NOT_FOUND)
    var mineResult: ApiResult<MyMoments> = ApiResult.Success(MyMoments(emptyList(), emptyList(), 6))
    var profileResult: ApiResult<ProfileMoments> = ApiResult.Success(ProfileMoments(emptyList(), emptyList()))
    var deleteResult: ApiResult<Unit> = ApiResult.Success(Unit)
    var keepResult: ApiResult<Moment>? = null
    var unkeepResult: ApiResult<Unit> = ApiResult.Success(Unit)
    val deleted = ArrayList<String>()
    val keptIds = ArrayList<String>()
    val unkeptIds = ArrayList<String>()
    val profileReads = ArrayList<String>()
    var mineReads = 0

    override suspend fun today(people: People, cursor: String?): ApiResult<TodayPage> {
        todayCalls += people to cursor
        return pages.removeFirstOrNull() ?: ApiResult.Success(TodayPage(null, emptyList(), null))
    }

    override suspend fun moment(id: String): ApiResult<Moment> = momentResult

    override suspend fun mine(): ApiResult<MyMoments> {
        mineReads++
        return mineResult
    }

    override suspend fun ofProfile(profileId: String): ApiResult<ProfileMoments> {
        profileReads += profileId
        return profileResult
    }

    override suspend fun delete(id: String): ApiResult<Unit> {
        deleted += id
        return deleteResult
    }

    override suspend fun keep(id: String): ApiResult<Moment> {
        keptIds += id
        return keepResult ?: (momentResult as? ApiResult.Success)?.let { ApiResult.Success(it.data.copy(kept = true)) }
            ?: ApiResult.Failure(ApiError.NOT_FOUND)
    }

    override suspend fun unkeep(id: String): ApiResult<Unit> {
        unkeptIds += id
        return unkeepResult
    }

    override fun photoUrl(momentId: String): String? = "https://test.invalid/api/moments/$momentId/media"

    override fun personPhotoUrl(profileId: String, key: String): String? =
        "https://test.invalid/api/profiles/$profileId/photo/$key"
}
