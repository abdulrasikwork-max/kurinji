package com.safesphere.app.feature.intelligence.data.remote

import retrofit2.http.GET
import retrofit2.http.Query

/** Phase 8D. Same Retrofit/OkHttp client as every other API (JWT added by AuthInterceptor). */
interface IntelligenceApi {

    /** [regionCell] = one 5-character geohash. Never a route, a start or a destination. */
    @GET("api/intelligence/region")
    suspend fun region(@Query("cell") regionCell: String): RegionPackDto
}
