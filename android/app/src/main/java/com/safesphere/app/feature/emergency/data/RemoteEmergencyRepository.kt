package com.safesphere.app.feature.emergency.data

import com.safesphere.app.core.network.ApiCallHandler
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.emergency.data.remote.CreateEmergencyRequestDto
import com.safesphere.app.feature.emergency.data.remote.EmergencyApi
import com.safesphere.app.feature.emergency.data.remote.EndEmergencyRequestDto
import com.safesphere.app.feature.emergency.domain.EmergencyEvent
import com.safesphere.app.feature.emergency.domain.EmergencyRepository
import com.safesphere.app.feature.emergency.domain.EmergencyStatus
import com.safesphere.app.feature.emergency.domain.EmergencyTrigger
import com.safesphere.app.feature.emergency.domain.SosRecordRequest
import java.time.Instant

class RemoteEmergencyRepository(
    private val api: EmergencyApi,
    private val handler: ApiCallHandler
) : EmergencyRepository {

    override suspend fun start(): ApiResult<EmergencyEvent> =
        handler.execute { api.start(CreateEmergencyRequestDto(EmergencyTrigger.MANUAL.name)).toDomain() }

    override suspend fun record(request: SosRecordRequest): ApiResult<EmergencyEvent> =
        handler.execute {
            api.start(
                CreateEmergencyRequestDto(
                    triggerType = EmergencyTrigger.MANUAL.name,
                    clientRef = request.clientRef,
                    startedAt = request.startedAt?.toString(),
                    endedAt = request.ended?.at?.toString(),
                    outcome = request.ended?.let { if (it.resolved) EmergencyStatus.RESOLVED.name else EmergencyStatus.CANCELLED.name }
                )
            ).toDomain()
        }

    override suspend fun getHistory(): ApiResult<List<EmergencyEvent>> =
        handler.execute { api.getHistory().map { it.toDomain() } }

    override suspend fun cancel(id: Long, password: String?, endedAt: Instant?): ApiResult<EmergencyEvent> =
        handler.execute { api.cancel(id, EndEmergencyRequestDto(password, endedAt?.toString())).toDomain() }

    override suspend fun resolve(id: Long, password: String?, endedAt: Instant?): ApiResult<EmergencyEvent> =
        handler.execute { api.resolve(id, EndEmergencyRequestDto(password, endedAt?.toString())).toDomain() }
}
