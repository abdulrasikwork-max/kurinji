package com.safesphere.app.feature.intelligence.domain

import com.safesphere.app.core.network.ApiResult

/** Phase 8D. The only thing ever sent is one 5-character region cell per call. */
interface IntelligenceRepository {
    suspend fun region(regionCell: String): ApiResult<RegionPack>
}
