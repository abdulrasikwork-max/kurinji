package com.safesphere.app.feature.moments.data

import com.safesphere.app.core.network.ApiCallHandler
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.moments.data.remote.ResponseApi
import com.safesphere.app.feature.moments.data.remote.TextBodyDto
import com.safesphere.app.feature.moments.data.remote.uuid
import com.safesphere.app.feature.moments.domain.Moment
import com.safesphere.app.feature.moments.domain.PlanPeople
import com.safesphere.app.feature.moments.domain.ResponseRepository

/**
 * Phase N5: responses on the backend. Ids that are not UUIDs never reach a URL (NOT_FOUND here instead). An answer this
 * app cannot read is an error, never a half-shown moment.
 */
class RemoteResponseRepository(private val api: ResponseApi, private val handler: ApiCallHandler) : ResponseRepository {

    override suspend fun same(momentId: String, on: Boolean) =
        moment(momentId) { id -> if (on) api.same(id) else api.notSame(id) }

    override suspend fun sayMore(momentId: String, on: Boolean) =
        moment(momentId) { id -> if (on) api.sayMore(id) else api.noSayMore(id) }

    override suspend fun addMore(momentId: String, text: String) =
        moment(momentId) { id -> api.addMore(id, TextBodyDto(text.trim())) }

    override suspend fun askIn(momentId: String) = moment(momentId) { id -> api.askIn(id) }

    override suspend fun leave(momentId: String): ApiResult<Unit> {
        val id = uuid(momentId) ?: return ApiResult.Failure(ApiError.NOT_FOUND)
        return handler.execute { api.leave(id) }
    }

    override suspend fun people(momentId: String) = people(momentId, null) { id, _ -> api.people(id) }

    override suspend fun add(momentId: String, ref: String) = people(momentId, ref) { id, r -> api.add(id, r!!) }

    override suspend fun setAside(momentId: String, ref: String, aside: Boolean) = people(momentId, ref) { id, r ->
        if (aside) api.setAside(id, r!!) else api.bringBack(id, r!!)
    }

    override suspend fun remove(momentId: String, ref: String) = people(momentId, ref) { id, r -> api.remove(id, r!!) }

    override suspend fun callOff(momentId: String) = moment(momentId) { id -> api.callOff(id) }

    private suspend fun moment(momentId: String, call: suspend (String) -> com.safesphere.app.feature.moments.data.remote.MomentDto): ApiResult<Moment> {
        val id = uuid(momentId) ?: return ApiResult.Failure(ApiError.NOT_FOUND)
        return when (val result = handler.execute { call(id).toDomain() }) {
            is ApiResult.Success -> result.data?.let { ApiResult.Success(it) } ?: ApiResult.Failure(ApiError.UNKNOWN)
            is ApiResult.Failure -> result
        }
    }

    private suspend fun people(
        momentId: String,
        ref: String?,
        call: suspend (String, String?) -> com.safesphere.app.feature.moments.data.remote.PeopleDto
    ): ApiResult<PlanPeople> {
        val id = uuid(momentId) ?: return ApiResult.Failure(ApiError.NOT_FOUND)
        val safeRef = if (ref == null) null else uuid(ref) ?: return ApiResult.Failure(ApiError.NOT_FOUND)
        return handler.execute { call(id, safeRef).toDomain() }
    }
}
