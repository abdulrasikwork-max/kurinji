package com.safesphere.app.core.push

import com.safesphere.app.core.network.ApiCallHandler
import com.safesphere.app.core.network.ApiResult
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PUT

/** N7 (B1): the phone's token for THIS sign-in and the person's switches. */
interface PushApi {

    @PUT("api/push/device")
    suspend fun register(@Body body: PushDeviceDto)

    @DELETE("api/push/device")
    suspend fun unregister()

    @GET("api/push/settings")
    suspend fun settings(): PushSettingsDto

    @PUT("api/push/settings")
    suspend fun change(@Body body: PushSettingsDto): PushSettingsDto
}

data class PushDeviceDto(val token: String, val appVersion: String?) {
    override fun toString() = "PushDeviceDto(token=<hidden>, appVersion=$appVersion)"
}

data class PushSettingsDto(
    val replies: Boolean?,
    val plans: Boolean?,
    val planMessages: Boolean?,
    val people: Boolean?,
    val staff: Boolean?
)

/** The server's switches (D31): "off" means IRL does not send it. */
data class PushSwitches(
    val replies: Boolean = true,
    val plans: Boolean = true,
    val planMessages: Boolean = true,
    val people: Boolean = true,
    val staff: Boolean = false
)

class PushRepository(private val api: PushApi, private val calls: ApiCallHandler) {

    suspend fun register(token: String, appVersion: String?): ApiResult<Unit> =
        calls.execute { api.register(PushDeviceDto(token, appVersion)) }

    suspend fun unregister(): ApiResult<Unit> = calls.execute { api.unregister() }

    suspend fun switches(): ApiResult<PushSwitches> = calls.execute { api.settings().toSwitches() }

    suspend fun change(s: PushSwitches): ApiResult<PushSwitches> = calls.execute {
        api.change(PushSettingsDto(s.replies, s.plans, s.planMessages, s.people, s.staff)).toSwitches()
    }

    private fun PushSettingsDto.toSwitches() = PushSwitches(
        replies = replies ?: true, plans = plans ?: true, planMessages = planMessages ?: true,
        people = people ?: true, staff = staff ?: false
    )
}
