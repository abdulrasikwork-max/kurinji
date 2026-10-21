package com.safesphere.app.feature.community.domain

import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.intelligence.domain.AreaPicture

/** One area answer: the signals for the list and radar, and the picture (null = none from the server). */
data class AreaView(val signals: List<AreaSignal>, val picture: AreaPicture?)

/**
 * Phase 6 backend calls. Every location argument is a geohash CELL computed on the phone; no coordinate is ever sent.
 */
interface CommunityRepository {

    /**
     * Visible signals around the 6-character [areaCell] (the server adds the 8 neighbouring cells), plus the Phase 8
     * area picture for the same neighbourhood.
     */
    suspend fun area(areaCell: String): ApiResult<AreaView>

    suspend fun report(category: SignalCategory, blockCell: String): ApiResult<ContributionAccepted>

    /** [nearCell] = the person's current 7-character cell; the server only checks it is near the signal. */
    suspend fun contribute(signalId: String, kind: ContributionKind, nearCell: String): ApiResult<ContributionAccepted>

    suspend fun mine(): ApiResult<List<MyContribution>>

    suspend fun withdraw(signalId: String): ApiResult<Unit>

    /** Phase 7: flags a visible signal; [nearCell] = the person's current 7-character cell (checked, never stored). */
    suspend fun flag(signalId: String, reason: FlagReason, nearCell: String): ApiResult<Unit>

    /** Phase 7: whether a moderator paused the person's contributions, and their appeal. */
    suspend fun standing(): ApiResult<CommunityStanding>

    /** Phase 7: asks a different moderator to review the current restriction. */
    suspend fun appeal(): ApiResult<Unit>
}

/** A report made without a connection, kept until it can be sent (cell only, never a coordinate). */
data class PendingReport(val category: SignalCategory, val blockCell: String, val createdAtMillis: Long)

/** The offline outbox for reports. Cleared on logout. */
interface CommunityOutbox {
    suspend fun load(): List<PendingReport>
    suspend fun save(reports: List<PendingReport>)
    suspend fun clear()
}
