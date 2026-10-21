package com.safesphere.app.testing

import com.safesphere.app.core.escalation.EscalationLevel
import com.safesphere.app.core.location.GeoPoint
import com.safesphere.app.core.location.LocationFix
import com.safesphere.app.feature.safewalk.domain.ConcernKind
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.safewalk.domain.Journey
import com.safesphere.app.feature.safewalk.domain.JourneyRepository
import com.safesphere.app.feature.safewalk.domain.JourneyStatus
import java.time.Duration
import java.time.Instant

/**
 * A tiny in-memory "server" for one journey. Deadlines are computed with [serverNow] (the server's clock),
 * like the real backend. [offline] makes every call fail with NO_CONNECTION; [nextFailure] fails one call.
 */
class FakeJourneyRepository(var serverNow: () -> Instant) : JourneyRepository {

    var journey: Journey? = null
    var offline = false
    var nextFailure: ApiResult.Failure? = null
    val calls = mutableListOf<String>()
    val locations = mutableListOf<LocationFix>()

    override suspend fun start(destinationLabel: String?, checkInIntervalMinutes: Int, destination: GeoPoint?): ApiResult<Journey> = call("start") {
        val now = serverNow()
        Journey(
            id = 1, status = JourneyStatus.ACTIVE, destinationLabel = destinationLabel,
            checkInIntervalMinutes = checkInIntervalMinutes, escalationLevel = EscalationLevel.NORMAL,
            startedAt = now, endedAt = null, nextCheckInDueAt = now.plus(Duration.ofMinutes(checkInIntervalMinutes.toLong())),
            serverTime = now, destination = destination
        ).also { journey = it }
    }

    override suspend fun getHistory(): ApiResult<List<Journey>> = call("history") {
        listOfNotNull(journey?.copy(serverTime = serverNow()))
    }

    override suspend fun checkIn(id: Long): ApiResult<Journey> = call("checkIn") {
        val now = serverNow()
        active(id).copy(
            escalationLevel = EscalationLevel.NORMAL,
            nextCheckInDueAt = now.plus(Duration.ofMinutes(active(id).checkInIntervalMinutes.toLong())),
            serverTime = now
        ).also { journey = it }
    }

    override suspend fun complete(id: Long): ApiResult<Journey> = call("complete") { end(id, JourneyStatus.COMPLETED) }

    override suspend fun cancel(id: Long): ApiResult<Journey> = call("cancel") { end(id, JourneyStatus.CANCELLED) }

    /** Recorded as "escalate:1" (missed check-in) or "escalate:1:ROUTE_DEVIATION" (a movement concern). */
    override suspend fun escalate(id: Long, level: EscalationLevel, concern: ConcernKind?): ApiResult<Journey> =
        call("escalate:${level.code}" + (concern?.let { ":${it.name}" } ?: "")) {
            active(id).copy(escalationLevel = level, serverTime = serverNow()).also { journey = it }
        }

    override suspend fun updateLocation(id: Long, fix: LocationFix): ApiResult<Unit> = call("location") {
        active(id)
        locations += fix
    }

    fun endOnServer(status: JourneyStatus) {
        journey = journey?.copy(status = status, nextCheckInDueAt = null)
    }

    private fun end(id: Long, status: JourneyStatus): Journey {
        active(id)
        return journey!!.copy(status = status, nextCheckInDueAt = null, endedAt = serverNow(), serverTime = serverNow())
            .also { journey = it }
    }

    private fun active(id: Long): Journey {
        val current = journey
        if (current == null || current.id != id) throw NotFound()
        if (current.status != JourneyStatus.ACTIVE) throw Conflict()
        return current
    }

    private class NotFound : RuntimeException()
    private class Conflict : RuntimeException()

    private fun <T> call(name: String, block: () -> T): ApiResult<T> {
        calls += name
        if (offline) return ApiResult.Failure(ApiError.NO_CONNECTION)
        nextFailure?.let { nextFailure = null; return it }
        return try {
            ApiResult.Success(block())
        } catch (e: NotFound) {
            ApiResult.Failure(ApiError.NOT_FOUND)
        } catch (e: Conflict) {
            ApiResult.Failure(ApiError.CONFLICT, "This Safe Walk has already ended")
        }
    }
}
