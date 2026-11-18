package com.safesphere.app.feature.safewalk.data

import com.safesphere.app.core.network.ApiCallHandler
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.safewalk.data.remote.JourneyApi
import com.safesphere.app.feature.safewalk.data.remote.StartJourneyRequestDto
import com.safesphere.app.feature.safewalk.domain.Journey
import com.safesphere.app.feature.safewalk.domain.JourneyRepository

class RemoteJourneyRepository(
    private val api: JourneyApi,
    private val handler: ApiCallHandler
) : JourneyRepository {

    override suspend fun start(destinationLabel: String?, checkInIntervalMinutes: Int): ApiResult<Journey> =
        handler.execute { api.start(StartJourneyRequestDto(destinationLabel, checkInIntervalMinutes)).toDomain() }

    override suspend fun getHistory(): ApiResult<List<Journey>> =
        handler.execute { api.getHistory().map { it.toDomain() } }

    override suspend fun checkIn(id: Long): ApiResult<Journey> = handler.execute { api.checkIn(id).toDomain() }

    override suspend fun complete(id: Long): ApiResult<Journey> = handler.execute { api.complete(id).toDomain() }

    override suspend fun cancel(id: Long): ApiResult<Journey> = handler.execute { api.cancel(id).toDomain() }
}
