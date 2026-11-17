package com.safesphere.app.feature.moments.data

import com.safesphere.app.core.network.ApiCallHandler
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.moments.data.remote.MomentApi
import com.safesphere.app.feature.moments.data.remote.MomentRequestDto
import com.safesphere.app.feature.moments.data.remote.uuid
import com.safesphere.app.feature.moments.domain.Moment
import com.safesphere.app.feature.moments.domain.MomentDraft
import com.safesphere.app.feature.moments.domain.MomentRepository
import com.safesphere.app.feature.moments.domain.MyMoments
import com.safesphere.app.feature.moments.domain.People
import com.safesphere.app.feature.moments.domain.ProfileMoments
import com.safesphere.app.feature.moments.domain.TodayPage
import com.safesphere.app.feature.moments.domain.UploadedPhoto
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/** Phase N4: moments on the backend. All error handling is in [ApiCallHandler]. Nothing is cached or stored. */
class RemoteMomentRepository(
    private val api: MomentApi,
    private val handler: ApiCallHandler,
    private val baseUrl: String
) : MomentRepository {

    override suspend fun today(people: People, cursor: String?): ApiResult<TodayPage> =
        handler.execute { api.today(people.name, cursor).toDomain() }

    override suspend fun moment(id: String): ApiResult<Moment> = understood(handler.execute { api.moment(id).toDomain() })

    override suspend fun mine(): ApiResult<MyMoments> = handler.execute { api.mine().toDomain() }

    override suspend fun ofProfile(profileId: String): ApiResult<ProfileMoments> =
        handler.execute { api.ofProfile(profileId).toDomain() }

    override suspend fun delete(id: String): ApiResult<Unit> = handler.execute { api.delete(id) }

    override suspend fun keep(id: String): ApiResult<Moment> = understood(handler.execute { api.keep(id).toDomain() })

    override suspend fun unkeep(id: String): ApiResult<Unit> = handler.execute { api.unkeep(id) }

    override suspend fun uploadPhoto(jpeg: ByteArray): ApiResult<UploadedPhoto> = when (val result =
        handler.execute { api.uploadPhoto(jpeg.toRequestBody("image/jpeg".toMediaType())).toDomain() }) {
        is ApiResult.Success -> result.data?.let { ApiResult.Success(it) } ?: ApiResult.Failure(ApiError.UNKNOWN)
        is ApiResult.Failure -> result
    }

    override suspend fun discardPhoto(mediaId: String): ApiResult<Unit> {
        val id = uuid(mediaId) ?: return ApiResult.Failure(ApiError.NOT_FOUND)
        return handler.execute { api.discardPhoto(id) }
    }

    override suspend fun share(draft: MomentDraft): ApiResult<Moment> =
        understood(handler.execute { api.share(MomentRequestDto.from(draft)).toDomain() })

    override fun photoUrl(momentId: String): String? =
        uuid(momentId)?.let { baseUrl.trimEnd('/') + "/api/moments/" + it + "/media" }

    override fun personPhotoUrl(profileId: String, key: String): String? {
        val id = uuid(profileId) ?: return null
        val photo = uuid(key) ?: return null
        return baseUrl.trimEnd('/') + "/api/profiles/" + id + "/photo/" + photo
    }

    /** An answer this app cannot read is an error, never a half-shown moment. */
    private fun understood(result: ApiResult<Moment?>): ApiResult<Moment> = when (result) {
        is ApiResult.Success -> result.data?.let { ApiResult.Success(it) } ?: ApiResult.Failure(ApiError.UNKNOWN)
        is ApiResult.Failure -> result
    }
}
