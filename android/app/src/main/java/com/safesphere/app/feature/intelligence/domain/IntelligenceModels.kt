package com.safesphere.app.feature.intelligence.domain

import com.safesphere.app.feature.community.domain.SignalCategory
import com.safesphere.app.feature.community.domain.SignalConfidence
import com.safesphere.app.feature.community.domain.SignalFreshness

/**
 * Phase 8 Safety Intelligence, as the app sees it (docs/PHASE8_DESIGN.md §5). Same names as the backend's rules-1.
 * Two separate axes, never one number: how much concern is CONFIRMED, and how much is KNOWN at all. None of the
 * outlooks means "safe": the app cannot know that a place is safe.
 */
enum class AreaOutlook { NO_INFORMATION, LITTLE_INFORMATION, NO_CONFIRMED_CONCERNS, TAKE_CARE, TAKE_EXTRA_CARE }

enum class ConcernLevel { NONE_CONFIRMED, SOME, ELEVATED }

enum class InformationLevel { NONE, LITTLE, SOME, MORE }

/** Why the picture is what it is (aggregate facts about places only). The order is the reading order. */
enum class PictureReason {
    CONFIRMED_INCIDENT_TODAY,
    CONFIRMED_INCIDENT_THIS_WEEK,
    CONFIRMED_CONDITION,
    CONDITIONS_MAY_HAVE_CHANGED,
    CONFLICTING_REPORTS,
    RECURRING_CONCERNS,
    UNCONFIRMED_REPORTS,
    SAFE_PLACES_NEARBY,
    LITTLE_INFORMATION
}

/** One ~1 km area cell of the neighbourhood (for the radar's shading and outline). */
data class AreaCellPicture(val cell: String, val outlook: AreaOutlook, val information: InformationLevel) {
    val needsCare: Boolean get() = outlook == AreaOutlook.TAKE_CARE || outlook == AreaOutlook.TAKE_EXTRA_CARE

    override fun toString() = "AreaCellPicture(cell=<redacted>, outlook=$outlook, information=$information)"
}

/**
 * The neighbourhood picture. [highestConcernCell] is one of the area cells in [areas] (never finer), or null when
 * nothing is confirmed. Lives in memory only: a stored picture would itself say where the person was.
 */
data class AreaPicture(
    val outlook: AreaOutlook,
    val concern: ConcernLevel,
    val information: InformationLevel,
    val reasons: List<PictureReason>,
    val concernCategories: List<SignalCategory>,
    val conflicting: Boolean,
    val highestConcernCell: String?,
    val areas: List<AreaCellPicture>
) {
    val needsCare: Boolean get() = outlook == AreaOutlook.TAKE_CARE || outlook == AreaOutlook.TAKE_EXTRA_CARE

    override fun toString() =
        "AreaPicture(outlook=$outlook, concern=$concern, information=$information, reasons=$reasons, cells=<redacted>)"
}

// ---- Phase 8D: region packs ("Check the way") --------------------------------------------------------------------

/** One ~1 km area of a region pack. Areas the community knows nothing about are absent from the pack. */
data class RegionArea(
    val cell: String,
    val outlook: AreaOutlook,
    val concern: ConcernLevel,
    val information: InformationLevel,
    val reasons: List<PictureReason>,
    val concernCategories: List<SignalCategory>,
    val conflicting: Boolean
) {
    override fun toString() = "RegionArea(cell=<redacted>, outlook=$outlook, information=$information)"
}

/** One CONFIRMED signal of a region pack (MODERATE or HIGH), at the block precision it is public at. No id. */
data class RegionSignal(
    val category: SignalCategory,
    val cell: String,
    val confidence: SignalConfidence,
    val freshness: SignalFreshness,
    val disputed: Boolean
) {
    override fun toString() = "RegionSignal(category=$category, cell=<redacted>, confidence=$confidence)"
}

/**
 * What the server knows about one ~5 km region. [complete] false = something is missing (too much to send, or a value
 * this app does not understand): no brief is then shown, because a brief that might miss a concern is worse than none.
 * Lives in memory only.
 */
data class RegionPack(val region: String, val complete: Boolean, val areas: List<RegionArea>, val signals: List<RegionSignal>) {
    override fun toString() = "RegionPack(region=<redacted>, complete=$complete, areas=${areas.size}, signals=${signals.size})"
}
