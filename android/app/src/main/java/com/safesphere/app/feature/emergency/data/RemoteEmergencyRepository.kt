package com.safesphere.app.feature.emergency.data

import com.safesphere.app.core.network.ApiCallHandler
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.emergency.data.remote.CreateEmergencyRequestDto
import com.safesphere.app.feature.emergency.data.remote.EmergencyApi
import com.safesphere.app.feature.emergency.data.remote.EndEmergencyRequestDto
import com.safesphere.app.feature.emergency.domain.EmergencyEvent
import com.safesphere.app.feature.emergency.domain.EmergencyRepository
import com.safesphere.app.feature.emergency.domain.EmergencyTrigger

class RemoteEmergencyRepository(
    private val api: EmergencyApi,
    private val handler: ApiCallHandler
) : EmergencyRepository {

    override suspend fun start(): ApiResult<EmergencyEvent> =
        handler.execute { api.start(CreateEmergencyRequestDto(EmergencyTrigger.MANUAL.name)).toDomain() }

    override suspend fun getHistory(): ApiResult<List<EmergencyEvent>> =
        handler.execute { api.getHistory().map { it.toDomain() } }

    override suspend fun cancel(id: Long, password: String?): ApiResult<EmergencyEvent> =
        handler.execute { api.cancel(id, EndEmergencyRequestDto(password)).toDomain() }

    override suspend fun resolve(id: Long, password: String?): ApiResult<EmergencyEvent> =
        handler.execute { api.resolve(id, EndEmergencyRequestDto(password)).toDomain() }
}
