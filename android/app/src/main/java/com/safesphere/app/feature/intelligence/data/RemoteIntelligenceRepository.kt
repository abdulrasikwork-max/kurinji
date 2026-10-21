package com.safesphere.app.feature.intelligence.data

import com.safesphere.app.core.network.ApiCallHandler
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.intelligence.data.remote.IntelligenceApi
import com.safesphere.app.feature.intelligence.domain.IntelligenceRepository
import com.safesphere.app.feature.intelligence.domain.RegionPack

/** Region packs on the backend. All error handling is in [ApiCallHandler]. */
class RemoteIntelligenceRepository(
    private val api: IntelligenceApi,
    private val handler: ApiCallHandler
) : IntelligenceRepository {

    override suspend fun region(regionCell: String): ApiResult<RegionPack> =
        handler.execute { api.region(regionCell).toDomain(regionCell) }
}
