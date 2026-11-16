package com.safesphere.app.feature.emergency.domain

import com.safesphere.app.core.confirm.Confirmation
import com.safesphere.app.core.network.ApiResult
import java.time.Instant

/** Recording of emergency sessions on the server. The server records; only the phone may text the Safety Circle. */
interface EmergencyRepository {
    /** Records a new MANUAL emergency (Phase 3 shape, no reference). */
    suspend fun start(): ApiResult<EmergencyEvent>

    /**
     * Phase 11: records an SOS the phone already started. The same [SosRecordRequest.clientRef] always answers with
     * the same emergency, so re-sending after a lost answer is safe.
     */
    suspend fun record(request: SosRecordRequest): ApiResult<EmergencyEvent>

    /** The person's own emergencies, newest first. */
    suspend fun getHistory(): ApiResult<List<EmergencyEvent>>

    /**
     * "False alarm". Phase 10A.4 (N2): the phone that started the SOS sends no [confirmation]; from another sign-in the
     * server answers FORBIDDEN until a fresh texted code is sent. Phase 11: [endedAt] = when it was ended on a phone that
     * was offline at that moment (null = now).
     */
    suspend fun cancel(id: Long, confirmation: Confirmation? = null, endedAt: Instant? = null): ApiResult<EmergencyEvent>

    /** "I'm safe" (same rules as [cancel]). */
    suspend fun resolve(id: Long, confirmation: Confirmation? = null, endedAt: Instant? = null): ApiResult<EmergencyEvent>
}
