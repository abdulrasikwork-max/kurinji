package com.safesphere.app.feature.emergency.data

import com.safesphere.app.core.network.ApiCallHandler
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.emergency.data.remote.EmergencyApi
import com.safesphere.app.feature.emergency.data.remote.LocationBatchRequestDto
import com.safesphere.app.feature.emergency.data.remote.LocationPointDto
import com.safesphere.app.feature.emergency.domain.EmergencyLocationPoint
import com.safesphere.app.feature.emergency.domain.EmergencyLocationRepository
import com.safesphere.app.feature.emergency.domain.LocationUploadResult

class RemoteEmergencyLocationRepository(
    private val api: EmergencyApi,
    private val handler: ApiCallHandler
) : EmergencyLocationRepository {

    override suspend fun upload(
        emergencyId: Long,
        points: List<EmergencyLocationPoint>
    ): ApiResult<LocationUploadResult> {
        require(points.size in 1..EmergencyLocationRepository.MAX_BATCH_SIZE) { "1 to 50 points per upload" }
        return handler.execute {
            val response = api.uploadLocations(emergencyId, LocationBatchRequestDto(points.map(LocationPointDto::from)))
            LocationUploadResult(response.received, response.stored)
        }
    }
}
