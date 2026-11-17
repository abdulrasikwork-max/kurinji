package com.safesphere.app.feature.moments.data.remote

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

/** Phase N5: Same, Say more, the follow-up, I'm in and the author's people (docs/API.md "Phase N5"). One Retrofit. */
interface ResponseApi {

    @PUT("api/moments/{id}/same")
    suspend fun same(@Path("id") id: String): MomentDto

    @DELETE("api/moments/{id}/same")
    suspend fun notSame(@Path("id") id: String): MomentDto

    @PUT("api/moments/{id}/say-more")
    suspend fun sayMore(@Path("id") id: String): MomentDto

    @DELETE("api/moments/{id}/say-more")
    suspend fun noSayMore(@Path("id") id: String): MomentDto

    @POST("api/moments/{id}/more")
    suspend fun addMore(@Path("id") id: String, @Body body: TextBodyDto): MomentDto

    @PUT("api/moments/{id}/in")
    suspend fun askIn(@Path("id") id: String): MomentDto

    @DELETE("api/moments/{id}/in")
    suspend fun leave(@Path("id") id: String)

    @GET("api/moments/{id}/people")
    suspend fun people(@Path("id") id: String): PeopleDto

    @POST("api/moments/{id}/people/{ref}/add")
    suspend fun add(@Path("id") id: String, @Path("ref") ref: String): PeopleDto

    @POST("api/moments/{id}/people/{ref}/set-aside")
    suspend fun setAside(@Path("id") id: String, @Path("ref") ref: String): PeopleDto

    @DELETE("api/moments/{id}/people/{ref}/set-aside")
    suspend fun bringBack(@Path("id") id: String, @Path("ref") ref: String): PeopleDto

    @DELETE("api/moments/{id}/people/{ref}")
    suspend fun remove(@Path("id") id: String, @Path("ref") ref: String): PeopleDto

    @POST("api/moments/{id}/call-off")
    suspend fun callOff(@Path("id") id: String): MomentDto
}
