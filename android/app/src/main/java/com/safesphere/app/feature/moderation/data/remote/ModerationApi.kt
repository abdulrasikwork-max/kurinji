package com.safesphere.app.feature.moderation.data.remote

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/** Phase 7 moderation. Same Retrofit/OkHttp client as every other API (JWT added by AuthInterceptor). */
interface ModerationApi {

    @GET("api/moderation/access")
    suspend fun access(): AccessDto

    @GET("api/moderation/cases")
    suspend fun cases(@Query("status") status: String): List<CaseSummaryDto>

    @GET("api/moderation/cases/{id}")
    suspend fun caseDetail(@Path("id") caseId: String): CaseDetailDto

    @POST("api/moderation/cases/{id}/actions")
    suspend fun act(@Path("id") caseId: String, @Body body: ActionRequestDto): CaseDetailDto

    @GET("api/moderation/appeals")
    suspend fun appeals(): List<AppealDto>

    @POST("api/moderation/appeals/{id}/decision")
    suspend fun decide(@Path("id") appealId: String, @Body body: AppealDecisionDto): AppealDto
}
