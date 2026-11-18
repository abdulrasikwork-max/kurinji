package com.safesphere.app.feature.safetycircle.data

import com.safesphere.app.core.network.ApiCallHandler
import com.safesphere.app.core.network.ApiResult
import java.time.Instant
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

/*
 * N7 (D36): the Safety Circle in the app, from the member's side. Someone added your verified number to their Circle
 * (a request), you accepted (a circle you are in), and an SOS of theirs that IRL texted you about (the same facts as the
 * text). Nothing here is stored on the phone (Q3); everything is read when shown.
 */

/** A request to you, or a circle you are in: an opaque id and the owner's real name. Nothing else. */
data class CircleRequest(val id: String, val ownerName: String)

enum class CircleSosStatus { ACTIVE, SAFE, FALSE_ALARM, ENDED }

/**
 * Someone's SOS as their Circle member sees it: name, their number (to call), when it started, where it stands, the
 * map link the texts carried (never newer), and "sent late". [phoneE164] null = no Call button (fail closed).
 */
data class CircleSos(
    val name: String,
    val phoneE164: String?,
    val startedAt: Instant,
    val status: CircleSosStatus,
    val mapLink: String?,
    val locationAt: Instant?,
    val sentLate: Boolean
) {
    override fun toString() = "CircleSos(status=$status, location=${if (mapLink != null) "<link>" else "none"})"
}

interface CircleInAppApi {
    @GET("api/circle/requests")
    suspend fun requests(): List<CircleRequestDto>

    @POST("api/circle/requests/{id}/accept")
    suspend fun accept(@Path("id") id: String)

    @POST("api/circle/requests/{id}/decline")
    suspend fun decline(@Path("id") id: String, @Body body: DeclineDto)

    @GET("api/circle/in")
    suspend fun circlesIn(): List<CircleRequestDto>

    @DELETE("api/circle/in/{id}")
    suspend fun leave(@Path("id") id: String)

    @GET("api/circle/alerts/{emergencyId}")
    suspend fun alert(@Path("emergencyId") emergencyId: Long): CircleSosDto
}

data class DeclineDto(val dontAskAgain: Boolean)

data class CircleRequestDto(val id: String?, val ownerName: String?) {
    /** Fail closed: a malformed row is dropped. */
    fun toDomain(): CircleRequest? {
        val id = id?.takeIf { UUID.matches(it) } ?: return null
        val name = ownerName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return CircleRequest(id, name)
    }

    private companion object {
        val UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    }
}

data class CircleSosDto(
    val name: String?,
    val phone: String?,
    val startedAt: String?,
    val status: String?,
    val mapLink: String?,
    val locationAt: String?,
    val sentLate: Boolean?
) {
    /**
     * Fail closed: no name or start time = unreadable (null); an unknown status reads as ENDED (never a made-up
     * emergency); only an Indian mobile number gets a Call button; only IRL's own map-link form is shown.
     */
    fun toDomain(): CircleSos? {
        val name = name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val started = startedAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
        return CircleSos(
            name = name,
            phoneE164 = phone?.takeIf { PHONE.matches(it) },
            startedAt = started,
            status = CircleSosStatus.entries.firstOrNull { it.name == status } ?: CircleSosStatus.ENDED,
            mapLink = mapLink?.takeIf { MAP.matches(it) },
            locationAt = locationAt?.let { runCatching { Instant.parse(it) }.getOrNull() },
            sentLate = sentLate == true
        )
    }

    override fun toString() = "CircleSosDto(status=$status)"

    companion object {
        val PHONE = Regex("^\\+91[6-9][0-9]{9}$")
        val MAP = Regex("^https://maps\\.google\\.com/\\?q=-?[0-9]{1,3}\\.[0-9]{1,6},-?[0-9]{1,3}\\.[0-9]{1,6}$")
    }
}

class CircleInAppRepository(private val api: CircleInAppApi, private val calls: ApiCallHandler) {

    suspend fun requests(): ApiResult<List<CircleRequest>> =
        calls.execute { api.requests().mapNotNull { it.toDomain() } }

    suspend fun accept(id: String): ApiResult<Unit> = calls.execute { api.accept(id) }

    suspend fun decline(id: String, dontAskAgain: Boolean): ApiResult<Unit> =
        calls.execute { api.decline(id, DeclineDto(dontAskAgain)) }

    suspend fun circlesIn(): ApiResult<List<CircleRequest>> =
        calls.execute { api.circlesIn().mapNotNull { it.toDomain() } }

    suspend fun leave(id: String): ApiResult<Unit> = calls.execute { api.leave(id) }

    /** NOT_FOUND = not (or no longer) yours to see; an unreadable answer is treated the same. */
    suspend fun alert(emergencyId: Long): ApiResult<CircleSos> =
        when (val r = calls.execute { api.alert(emergencyId).toDomain() }) {
            is ApiResult.Success -> r.data?.let { ApiResult.Success(it) }
                ?: ApiResult.Failure(com.safesphere.app.core.network.ApiError.NOT_FOUND)
            is ApiResult.Failure -> r
        }
}
