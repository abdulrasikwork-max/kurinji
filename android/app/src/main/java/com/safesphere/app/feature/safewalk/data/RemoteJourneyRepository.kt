package com.safesphere.app.feature.safewalk.data

import com.safesphere.app.core.escalation.EscalationLevel
import com.safesphere.app.core.location.GeoPoint
import com.safesphere.app.core.location.LocationFix
import com.safesphere.app.core.network.ApiCallHandler
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.safewalk.data.remote.EscalationRequestDto
import com.safesphere.app.feature.safewalk.data.remote.JourneyApi
import com.safesphere.app.feature.safewalk.data.remote.JourneyLocationDto
import com.safesphere.app.feature.safewalk.data.remote.StartJourneyRequestDto
import com.safesphere.app.feature.safewalk.domain.ConcernKind
import com.safesphere.app.feature.safewalk.domain.Journey
import com.safesphere.app.feature.safewalk.domain.JourneyRepository

class RemoteJourneyRepository(
    private val api: JourneyApi,
    private val handler: ApiCallHandler
) : JourneyRepository {

    override suspend fun start(destinationLabel: String?, checkInIntervalMinutes: Int, destination: GeoPoint?): ApiResult<Journey> =
        handler.execute {
            api.start(
                StartJourneyRequestDto(destinationLabel, checkInIntervalMinutes, destination?.latitude, destination?.longitude)
            ).toDomain()
        }

    override suspend fun getHistory(): ApiResult<List<Journey>> =
        handler.execute { api.getHistory().map { it.toDomain() } }

    override suspend fun checkIn(id: Long): ApiResult<Journey> = handler.execute { api.checkIn(id).toDomain() }

    override suspend fun complete(id: Long): ApiResult<Journey> = handler.execute { api.complete(id).toDomain() }

    override suspend fun cancel(id: Long): ApiResult<Journey> = handler.execute { api.cancel(id).toDomain() }

    override suspend fun escalate(id: Long, level: EscalationLevel, concern: ConcernKind?): ApiResult<Journey> =
        handler.execute { api.escalate(id, EscalationRequestDto(level.code, concern?.name)).toDomain() }

    override suspend fun updateLocation(id: Long, fix: LocationFix): ApiResult<Unit> =
        handler.execute { api.updateLocation(id, JourneyLocationDto.from(fix)) }
}
