package com.safesphere.app.feature.community.data.remote

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/** Phase 6 place signals. Same Retrofit/OkHttp client as every other API (JWT added by AuthInterceptor). */
interface CommunityApi {

    @GET("api/community/area")
    suspend fun area(@Query("cell") areaCell: String): AreaResponseDto

    @POST("api/community/reports")
    suspend fun report(@Body body: ReportRequestDto): AcceptedDto

    @POST("api/community/signals/{id}/contributions")
    suspend fun contribute(@Path("id") signalId: String, @Body body: ContributionRequestDto): AcceptedDto

    @GET("api/community/mine")
    suspend fun mine(): List<MyContributionDto>

    /** The server answers 204 No Content. */
    @DELETE("api/community/mine/{id}")
    suspend fun withdraw(@Path("id") signalId: String)

    // ---- Phase 7 ----

    @POST("api/community/signals/{id}/flags")
    suspend fun flag(@Path("id") signalId: String, @Body body: FlagRequestDto): FlagAcceptedDto

    @GET("api/community/standing")
    suspend fun standing(): StandingDto

    @POST("api/community/appeals")
    suspend fun appeal(@Body body: Map<String, String>): AppealAcceptedDto
}
