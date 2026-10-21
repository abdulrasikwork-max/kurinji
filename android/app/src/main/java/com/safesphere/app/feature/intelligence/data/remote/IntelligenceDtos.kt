package com.safesphere.app.feature.intelligence.data.remote

import com.safesphere.app.core.location.GeoHash
import com.safesphere.app.feature.community.data.remote.enumOrNull
import com.safesphere.app.feature.community.domain.SignalCategory
import com.safesphere.app.feature.community.domain.SignalConfidence
import com.safesphere.app.feature.community.domain.SignalFreshness
import com.safesphere.app.feature.intelligence.domain.RegionArea
import com.safesphere.app.feature.intelligence.domain.RegionPack
import com.safesphere.app.feature.intelligence.domain.RegionSignal
import com.safesphere.app.feature.intelligence.domain.AreaCellPicture
import com.safesphere.app.feature.intelligence.domain.AreaOutlook
import com.safesphere.app.feature.intelligence.domain.AreaPicture
import com.safesphere.app.feature.intelligence.domain.ConcernLevel
import com.safesphere.app.feature.intelligence.domain.InformationLevel
import com.safesphere.app.feature.intelligence.domain.PictureReason

/**
 * Phase 8 picture in GET /api/community/area. Mapping fails CLOSED: if the headline values are missing or unknown
 * (an older or newer server), there is no picture at all rather than a guessed one. Unknown reasons and categories
 * are dropped one by one; cells are checked to be 6-character geohashes. toString() keeps cells out of logs.
 */
data class AreaPictureDto(
    val modelVersion: String?,
    val outlook: String?,
    val concern: String?,
    val information: String?,
    val reasons: List<String>?,
    val concernCategories: List<String>?,
    val conflicting: Boolean?,
    val highestConcernCell: String?,
    val areas: List<AreaCellPictureDto>?
) {
    fun toDomain(): AreaPicture? {
        val outlook = enumOrNull<AreaOutlook>(outlook) ?: return null
        val concern = enumOrNull<ConcernLevel>(concern) ?: return null
        val information = enumOrNull<InformationLevel>(information) ?: return null
        if (!consistent(outlook, concern, information)) return null
        val cells = areas.orEmpty().mapNotNull { it.toDomain() }
        return AreaPicture(
            outlook = outlook,
            concern = concern,
            information = information,
            reasons = reasons.orEmpty().mapNotNull { enumOrNull<PictureReason>(it) }.distinct(),
            concernCategories = concernCategories.orEmpty().mapNotNull { SignalCategory.fromName(it) }.distinct(),
            conflicting = conflicting ?: false,
            highestConcernCell = highestConcernCell?.takeIf { cell -> cells.any { it.cell == cell } },
            areas = cells
        )
    }

    override fun toString() = "AreaPictureDto(outlook=$outlook, concern=$concern, information=$information, cells=<redacted>)"

    private companion object {
        /** The headline must agree with the two axes it is made of; anything else is not shown. */
        fun consistent(outlook: AreaOutlook, concern: ConcernLevel, information: InformationLevel): Boolean =
            when (outlook) {
                AreaOutlook.TAKE_EXTRA_CARE -> concern == ConcernLevel.ELEVATED
                AreaOutlook.TAKE_CARE -> concern == ConcernLevel.SOME
                AreaOutlook.NO_INFORMATION -> concern == ConcernLevel.NONE_CONFIRMED && information == InformationLevel.NONE
                AreaOutlook.LITTLE_INFORMATION -> concern == ConcernLevel.NONE_CONFIRMED && information == InformationLevel.LITTLE
                AreaOutlook.NO_CONFIRMED_CONCERNS -> concern == ConcernLevel.NONE_CONFIRMED &&
                    (information == InformationLevel.SOME || information == InformationLevel.MORE)
            }
    }
}

/**
 * Phase 8D: GET /api/intelligence/region. Mapping fails SAFE in the other direction from the picture: any value this
 * app does not understand makes the pack INCOMPLETE (no brief), because silently dropping a signal could hide a concern.
 */
data class RegionPackDto(
    val region: String?,
    val modelVersion: String?,
    val complete: Boolean?,
    val areas: List<RegionAreaDto>?,
    val signals: List<RegionSignalDto>?
) {
    fun toDomain(requested: String): RegionPack {
        val areaList = areas.orEmpty().map { it.toDomain() }
        val signalList = signals.orEmpty().map { it.toDomain() }
        val understood = region == requested && complete != null && areas != null && signals != null &&
            areaList.all { it != null } && signalList.all { it != null }
        return RegionPack(
            region = requested,
            complete = understood && complete == true,
            areas = areaList.filterNotNull(),
            signals = signalList.filterNotNull()
        )
    }

    override fun toString() = "RegionPackDto(region=<redacted>, complete=$complete)"
}

data class RegionAreaDto(
    val cell: String?,
    val outlook: String?,
    val concern: String?,
    val information: String?,
    val reasons: List<String>?,
    val concernCategories: List<String>?,
    val conflicting: Boolean?
) {
    /** Null = not understood (the whole pack then counts as incomplete). Unknown reasons alone are just dropped. */
    fun toDomain(): RegionArea? {
        if (!GeoHash.isValid(cell, GeoHash.AREA)) return null
        val categories = concernCategories.orEmpty().map { SignalCategory.fromName(it) }
        if (categories.any { it == null }) return null
        return RegionArea(
            cell = cell!!,
            outlook = enumOrNull<AreaOutlook>(outlook) ?: return null,
            concern = enumOrNull<ConcernLevel>(concern) ?: return null,
            information = enumOrNull<InformationLevel>(information) ?: return null,
            reasons = reasons.orEmpty().mapNotNull { enumOrNull<PictureReason>(it) },
            concernCategories = categories.filterNotNull(),
            conflicting = conflicting ?: false
        )
    }

    override fun toString() = "RegionAreaDto(cell=<redacted>, outlook=$outlook)"
}

data class RegionSignalDto(
    val category: String?,
    val group: String?,
    val cell: String?,
    val confidence: String?,
    val freshness: String?,
    val disputed: Boolean?
) {
    /** Null = not understood. Only MODERATE / HIGH are valid here (the server never sends single reports). */
    fun toDomain(): RegionSignal? {
        if (!GeoHash.isValid(cell, GeoHash.BLOCK)) return null
        val confidence = enumOrNull<SignalConfidence>(confidence) ?: return null
        if (confidence == SignalConfidence.LOW) return null
        return RegionSignal(
            category = SignalCategory.fromName(category) ?: return null,
            cell = cell!!,
            confidence = confidence,
            freshness = enumOrNull<SignalFreshness>(freshness) ?: return null,
            disputed = disputed ?: false
        )
    }

    override fun toString() = "RegionSignalDto(category=$category, cell=<redacted>)"
}

data class AreaCellPictureDto(val cell: String?, val outlook: String?, val information: String?) {
    fun toDomain(): AreaCellPicture? {
        if (!GeoHash.isValid(cell, GeoHash.AREA)) return null
        return AreaCellPicture(
            cell = cell!!,
            outlook = enumOrNull<AreaOutlook>(outlook) ?: return null,
            information = enumOrNull<InformationLevel>(information) ?: return null
        )
    }

    override fun toString() = "AreaCellPictureDto(cell=<redacted>, outlook=$outlook, information=$information)"
}
