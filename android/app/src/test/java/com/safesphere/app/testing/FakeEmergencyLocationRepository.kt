package com.safesphere.app.testing

import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.emergency.domain.EmergencyLocationPoint
import com.safesphere.app.feature.emergency.domain.EmergencyLocationRepository
import com.safesphere.app.feature.emergency.domain.LocationUploadResult

/** Records every upload. [nextResults] are used first (in order), then [defaultResult]. */
class FakeEmergencyLocationRepository : EmergencyLocationRepository {

    val uploads = mutableListOf<Pair<Long, List<EmergencyLocationPoint>>>()
    val nextResults = ArrayDeque<ApiResult<LocationUploadResult>>()
    var defaultResult: ApiResult<LocationUploadResult>? = null

    val uploadedPoints: List<EmergencyLocationPoint>
        get() = uploads.filterIndexed { index, _ -> succeeded.getOrElse(index) { false } }.flatMap { it.second }

    private val succeeded = mutableListOf<Boolean>()

    override suspend fun upload(emergencyId: Long, points: List<EmergencyLocationPoint>): ApiResult<LocationUploadResult> {
        uploads += emergencyId to points
        val result = nextResults.removeFirstOrNull()
            ?: defaultResult
            ?: ApiResult.Success(LocationUploadResult(points.size, points.size))
        succeeded += result is ApiResult.Success
        return result
    }
}
