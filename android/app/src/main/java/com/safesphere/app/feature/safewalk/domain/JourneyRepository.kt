package com.safesphere.app.feature.safewalk.domain

import com.safesphere.app.core.network.ApiResult

/** The person's own walks on the server ("On my way"). Nothing here contacts anyone and nothing sends a location. */
interface JourneyRepository {
    suspend fun start(destinationLabel: String?, checkInIntervalMinutes: Int): ApiResult<Journey>
    suspend fun getHistory(): ApiResult<List<Journey>>
    /** "More time": a new reminder one interval from now (server time). */
    suspend fun checkIn(id: Long): ApiResult<Journey>
    /** "I'm there". */
    suspend fun complete(id: Long): ApiResult<Journey>
    suspend fun cancel(id: Long): ApiResult<Journey>
}
