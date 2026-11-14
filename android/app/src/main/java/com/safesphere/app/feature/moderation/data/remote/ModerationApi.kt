package com.safesphere.app.feature.moderation.data.remote

import retrofit2.http.GET

/** Same Retrofit/OkHttp client as every other API (JWT added by AuthInterceptor). */
interface ModerationApi {

    @GET("api/moderation/access")
    suspend fun access(): AccessDto
}
