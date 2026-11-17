package com.safesphere.app.feature.replies.data

import com.safesphere.app.core.network.ApiCallHandler
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.moments.data.remote.uuid
import com.safesphere.app.feature.replies.domain.Conversation
import com.safesphere.app.feature.replies.domain.Message
import com.safesphere.app.feature.replies.domain.PrivateReplies
import com.safesphere.app.feature.replies.domain.RepliesPage
import com.safesphere.app.feature.replies.domain.RepliesRepository
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

/** Phase N5: Replies, plans, private replies and the setting (docs/API.md "Phase N5"). Created from the ONE Retrofit. */
interface RepliesApi {

    @GET("api/replies")
    suspend fun replies(): RepliesDto

    @GET("api/replies/marker")
    suspend fun marker(): MarkerDto

    @PUT("api/replies/opened")
    suspend fun opened()

    @GET("api/conversations/{id}")
    suspend fun conversation(@Path("id") id: String): ConversationDto

    @POST("api/conversations/{id}/messages")
    suspend fun send(@Path("id") id: String, @Body body: MessageBodyDto): MessageDto

    @POST("api/conversations/{id}/end")
    suspend fun end(@Path("id") id: String)

    @POST("api/moments/{id}/private-replies")
    suspend fun startReply(@Path("id") id: String, @Body body: MessageBodyDto): ConversationDto

    @GET("api/social-settings")
    suspend fun settings(): SettingsDto

    @PUT("api/social-settings")
    suspend fun setSettings(@Body body: SettingsDto): SettingsDto
}

/**
 * Phase N5 on the backend. Ids that are not UUIDs never reach a URL (NOT_FOUND instead); an answer this app cannot
 * read is an error (UNKNOWN), never a half-shown conversation. Nothing is stored on the phone.
 */
class RemoteRepliesRepository(private val api: RepliesApi, private val handler: ApiCallHandler) : RepliesRepository {

    override suspend fun replies(): ApiResult<RepliesPage> = handler.execute { api.replies().toDomain() }

    override suspend fun marker(): ApiResult<Boolean> = handler.execute { api.marker().fresh == true }

    override suspend fun opened(): ApiResult<Unit> = handler.execute { api.opened() }

    override suspend fun conversation(id: String): ApiResult<Conversation> {
        val safe = uuid(id) ?: return ApiResult.Failure(ApiError.NOT_FOUND)
        return readable(handler.execute { api.conversation(safe).toDomain() })
    }

    override suspend fun send(conversationId: String, text: String): ApiResult<Message> {
        val safe = uuid(conversationId) ?: return ApiResult.Failure(ApiError.NOT_FOUND)
        return readable(handler.execute { api.send(safe, MessageBodyDto(text.trim())).toDomain() })
    }

    override suspend fun end(conversationId: String): ApiResult<Unit> {
        val safe = uuid(conversationId) ?: return ApiResult.Failure(ApiError.NOT_FOUND)
        return handler.execute { api.end(safe) }
    }

    override suspend fun startReply(momentId: String, text: String): ApiResult<Conversation> {
        val safe = uuid(momentId) ?: return ApiResult.Failure(ApiError.NOT_FOUND)
        return readable(handler.execute { api.startReply(safe, MessageBodyDto(text.trim())).toDomain() })
    }

    override suspend fun privateReplies(): ApiResult<PrivateReplies> = handler.execute { api.settings().toDomain() }

    override suspend fun setPrivateReplies(value: PrivateReplies): ApiResult<PrivateReplies> =
        handler.execute { api.setSettings(SettingsDto(value.name)).toDomain() }

    private fun <T : Any> readable(result: ApiResult<T?>): ApiResult<T> = when (result) {
        is ApiResult.Success -> result.data?.let { ApiResult.Success(it) } ?: ApiResult.Failure(ApiError.UNKNOWN)
        is ApiResult.Failure -> result
    }
}
