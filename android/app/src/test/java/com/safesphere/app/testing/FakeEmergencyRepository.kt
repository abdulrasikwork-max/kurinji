package com.safesphere.app.testing

import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.emergency.domain.EmergencyEvent
import com.safesphere.app.feature.emergency.domain.EmergencyRepository
import com.safesphere.app.feature.emergency.domain.EmergencyStatus
import com.safesphere.app.feature.emergency.domain.EmergencyTrigger
import java.time.Instant

/** Test double for the emergency recording. Results are configurable; calls are counted. */
class FakeEmergencyRepository : EmergencyRepository {

    var startResult: ApiResult<EmergencyEvent> = ApiResult.Success(event())
    var historyResult: ApiResult<List<EmergencyEvent>> = ApiResult.Success(emptyList())
    var cancelResult: ApiResult<EmergencyEvent> = ApiResult.Success(event(status = EmergencyStatus.CANCELLED))
    var resolveResult: ApiResult<EmergencyEvent> = ApiResult.Success(event(status = EmergencyStatus.RESOLVED))

    var startCalls = 0
        private set
    var cancelCalls = 0
        private set
    var resolveCalls = 0
        private set

    override suspend fun start(): ApiResult<EmergencyEvent> {
        startCalls++
        return startResult
    }

    override suspend fun getHistory(): ApiResult<List<EmergencyEvent>> = historyResult

    override suspend fun cancel(id: Long): ApiResult<EmergencyEvent> {
        cancelCalls++
        return cancelResult
    }

    override suspend fun resolve(id: Long): ApiResult<EmergencyEvent> {
        resolveCalls++
        return resolveResult
    }

    companion object {
        fun event(id: Long = 10, status: EmergencyStatus = EmergencyStatus.ACTIVE) = EmergencyEvent(
            id = id,
            status = status,
            trigger = EmergencyTrigger.MANUAL,
            startedAt = Instant.parse("2026-10-15T10:00:00Z"),
            endedAt = if (status == EmergencyStatus.ACTIVE) null else Instant.parse("2026-10-15T10:05:00Z")
        )
    }
}
