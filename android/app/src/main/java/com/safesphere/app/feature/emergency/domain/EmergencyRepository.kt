package com.safesphere.app.feature.emergency.domain

import com.safesphere.app.core.network.ApiResult

/** Recording of emergency sessions on the server. Phase 3 only records; nobody is notified. */
interface EmergencyRepository {
    /** Records a new MANUAL emergency (called when the countdown reaches zero). */
    suspend fun start(): ApiResult<EmergencyEvent>

    /** The person's own emergencies, newest first. */
    suspend fun getHistory(): ApiResult<List<EmergencyEvent>>

    /**
     * "False alarm". Phase 10A.4: the phone that started the SOS sends no [password]; from another sign-in the server
     * answers FORBIDDEN until the account password is sent.
     */
    suspend fun cancel(id: Long, password: String? = null): ApiResult<EmergencyEvent>

    /** "I'm safe" (same password rule as [cancel]). */
    suspend fun resolve(id: Long, password: String? = null): ApiResult<EmergencyEvent>
}
