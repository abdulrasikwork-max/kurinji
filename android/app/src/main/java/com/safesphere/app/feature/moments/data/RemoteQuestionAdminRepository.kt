package com.safesphere.app.feature.moments.data

import com.safesphere.app.core.network.ApiCallHandler
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.moments.data.remote.uuid
import com.safesphere.app.feature.moments.domain.QuestionAdminRepository
import com.safesphere.app.feature.moments.domain.ScheduledQuestion
import java.time.LocalDate
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

/** Phase N4 (A3): `/api/admin/questions` (ADMIN from the database; the server checks the role on every call). */
interface QuestionAdminApi {
    @GET("api/admin/questions")
    suspend fun list(): List<QuestionAdminDto>

    @POST("api/admin/questions")
    suspend fun create(@Body body: QuestionRequestDto): QuestionAdminDto

    @DELETE("api/admin/questions/{id}")
    suspend fun delete(@Path("id") id: String)
}

data class QuestionRequestDto(val day: String, val text: String)

/** A row this app cannot read (no id, no day, no words) is dropped, never shown half-understood. */
data class QuestionAdminDto(val id: String? = null, val day: String? = null, val text: String? = null) {
    fun toDomain(): ScheduledQuestion? {
        val safeId = uuid(id) ?: return null
        val date = day?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
        val words = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return ScheduledQuestion(safeId, date, words)
    }
}

class RemoteQuestionAdminRepository(
    private val api: QuestionAdminApi,
    private val handler: ApiCallHandler
) : QuestionAdminRepository {

    override suspend fun list(): ApiResult<List<ScheduledQuestion>> =
        handler.execute { api.list().mapNotNull { it.toDomain() }.sortedBy { it.day } }

    override suspend fun schedule(day: LocalDate, text: String): ApiResult<ScheduledQuestion> =
        when (val result = handler.execute { api.create(QuestionRequestDto(day.toString(), text.trim())).toDomain() }) {
            is ApiResult.Success -> result.data?.let { ApiResult.Success(it) } ?: ApiResult.Failure(ApiError.UNKNOWN)
            is ApiResult.Failure -> result
        }

    override suspend fun delete(id: String): ApiResult<Unit> {
        val safeId = uuid(id) ?: return ApiResult.Failure(ApiError.NOT_FOUND)
        return handler.execute { api.delete(safeId) }
    }
}
