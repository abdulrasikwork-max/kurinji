package com.safesphere.app.feature.community.data.remote

import com.safesphere.app.core.location.GeoPoint
import com.safesphere.app.feature.community.domain.AppealStatus
import com.safesphere.app.feature.community.domain.AreaSignal
import com.safesphere.app.feature.community.domain.AreaView
import com.safesphere.app.feature.community.domain.CommunityStanding
import com.safesphere.app.feature.intelligence.data.remote.AreaPictureDto
import com.safesphere.app.feature.community.domain.ContributionAccepted
import com.safesphere.app.feature.community.domain.ContributionKind
import com.safesphere.app.feature.community.domain.ModerationReason
import com.safesphere.app.feature.community.domain.MyContribution
import com.safesphere.app.feature.community.domain.MyContributionStatus
import com.safesphere.app.feature.community.domain.SignalCategory
import com.safesphere.app.feature.community.domain.SignalConfidence
import com.safesphere.app.feature.community.domain.SignalFreshness
import com.safesphere.app.feature.community.domain.SignalPrecision
import com.safesphere.app.feature.community.domain.SignalReason
import com.safesphere.app.feature.community.domain.SignalSupport
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

/** Request bodies carry geohash cells only. toString() keeps cells out of logs as well. */
data class ReportRequestDto(val category: String, val cell: String) {
    override fun toString() = "ReportRequestDto(category=$category, cell=<redacted>)"
}

data class ContributionRequestDto(val kind: String, val near: String) {
    override fun toString() = "ContributionRequestDto(kind=$kind, near=<redacted>)"
}

data class AcceptedDto(val status: String?, val appearsWithinMinutes: Int?) {
    fun toDomain() = ContributionAccepted(appearsWithinMinutes ?: 0)
}

data class AreaResponseDto(
    val area: String?,
    val signals: List<PublicSignalDto>?,
    /** Phase 8: Safety Intelligence for the same neighbourhood (absent from an older server). */
    val picture: AreaPictureDto? = null
) {
    /** Unknown names (a newer server) are skipped rather than shown wrongly. */
    fun toDomain(): List<AreaSignal> = signals.orEmpty().mapNotNull { it.toDomain() }

    fun toView(): AreaView = AreaView(toDomain(), picture?.toDomain())
}

data class PublicSignalDto(
    val id: String?,
    val category: String?,
    val precision: String?,
    val cell: String?,
    val latitude: Double?,
    val longitude: Double?,
    val support: String?,
    val freshness: String?,
    val disputed: Boolean?,
    val yourContribution: String?,
    val confidence: String? = null,
    val reasons: List<String>? = null,
    val youFlagged: Boolean? = null
) {
    fun toDomain(): AreaSignal? {
        val category = SignalCategory.fromName(category) ?: return null
        return AreaSignal(
            id = id ?: return null,
            category = category,
            precision = enumOrNull<SignalPrecision>(precision) ?: return null,
            cell = cell ?: return null,
            centre = GeoPoint(latitude ?: return null, longitude ?: return null),
            support = enumOrNull<SignalSupport>(support) ?: SignalSupport.SINGLE,
            freshness = enumOrNull<SignalFreshness>(freshness) ?: SignalFreshness.EARLIER,
            disputed = disputed ?: false,
            yourContribution = enumOrNull<ContributionKind>(yourContribution),
            // An older server sends none of these: never claim more certainty than we were told.
            confidence = enumOrNull<SignalConfidence>(confidence) ?: SignalConfidence.LOW,
            reasons = reasons.orEmpty().mapNotNull { enumOrNull<SignalReason>(it) },
            youFlagged = youFlagged ?: false
        )
    }
}

/** Phase 7. [near] = the person's current cell; toString() keeps it out of logs. */
data class FlagRequestDto(val reason: String, val near: String) {
    override fun toString() = "FlagRequestDto(reason=$reason, near=<redacted>)"
}

data class FlagAcceptedDto(val status: String?)

data class AppealAcceptedDto(val status: String?)

/** [pausedUntil] is a UTC date-time without zone, as every server time in SafeSphere. */
data class StandingDto(
    val contributionsPaused: Boolean?,
    val pausedUntil: String?,
    val reason: String?,
    val appeal: String?,
    val canAppeal: Boolean?
) {
    fun toDomain() = CommunityStanding(
        contributionsPaused = contributionsPaused ?: false,
        pausedUntilMillis = pausedUntil?.let { parseUtcMillis(it) },
        reason = enumOrNull<ModerationReason>(reason),
        appeal = enumOrNull<AppealStatus>(appeal),
        canAppeal = canAppeal ?: false
    )
}

internal fun parseUtcMillis(text: String): Long? =
    try {
        LocalDateTime.parse(text).toInstant(ZoneOffset.UTC).toEpochMilli()
    } catch (e: DateTimeParseException) {
        null
    }

data class MyContributionDto(val signalId: String?, val category: String?, val kind: String?, val status: String?) {
    fun toDomain(): MyContribution? {
        return MyContribution(
            signalId = signalId ?: return null,
            category = SignalCategory.fromName(category) ?: return null,
            kind = enumOrNull<ContributionKind>(kind) ?: return null,
            status = enumOrNull<MyContributionStatus>(status) ?: MyContributionStatus.NOT_SHOWN
        )
    }
}

internal inline fun <reified E : Enum<E>> enumOrNull(name: String?): E? =
    enumValues<E>().firstOrNull { it.name == name }
