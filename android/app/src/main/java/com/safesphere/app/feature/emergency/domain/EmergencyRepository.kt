package com.safesphere.app.feature.emergency.domain

import com.safesphere.app.core.network.ApiResult

/** Recording of emergency sessions on the server. Phase 3 only records; nobody is notified. */
interface EmergencyRepository {
    /** Records a new MANUAL emergency (called when the countdown reaches zero). */
    suspend fun start(): ApiResult<EmergencyEvent>

    /** The person's own emergencies, newest first. */
    suspend fun getHistory(): ApiResult<List<EmergencyEvent>>

    /** "False alarm". */
    suspend fun cancel(id: Long): ApiResult<EmergencyEvent>

    /** "I'm safe". */
    suspend fun resolve(id: Long): ApiResult<EmergencyEvent>
}
