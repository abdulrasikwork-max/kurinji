package com.safesphere.app.feature.intelligence.ui

import android.content.Context
import androidx.annotation.StringRes
import com.safesphere.app.R
import com.safesphere.app.core.location.GeoPoint
import com.safesphere.app.core.location.LocationUnavailableReason
import com.safesphere.app.feature.community.ui.CommunityTexts
import com.safesphere.app.feature.intelligence.domain.CorridorBriefPolicy
import com.safesphere.app.feature.intelligence.domain.AreaOutlook
import com.safesphere.app.feature.intelligence.domain.AreaPicture
import com.safesphere.app.feature.intelligence.domain.InformationLevel
import com.safesphere.app.feature.intelligence.domain.IntelligencePolicy
import com.safesphere.app.feature.intelligence.domain.IntelligencePolicy.Compass
import com.safesphere.app.feature.intelligence.domain.PictureReason

/**
 * Words for the Phase 8 area picture. The server sends codes; the phone writes the words, so nothing the server says
 * can become free text on screen. Every string is checked by IntelligenceTextsTest (no "safe" claims).
 */
object IntelligenceTexts {

    @StringRes
    fun headline(outlook: AreaOutlook): Int = when (outlook) {
        AreaOutlook.NO_INFORMATION -> R.string.outlook_no_information
        AreaOutlook.LITTLE_INFORMATION -> R.string.outlook_little_information
        AreaOutlook.NO_CONFIRMED_CONCERNS -> R.string.outlook_no_confirmed_concerns
        AreaOutlook.TAKE_CARE -> R.string.outlook_take_care
        AreaOutlook.TAKE_EXTRA_CARE -> R.string.outlook_take_extra_care
    }

    @StringRes
    fun information(level: InformationLevel): Int = when (level) {
        InformationLevel.NONE -> R.string.information_none
        InformationLevel.LITTLE -> R.string.information_little
        InformationLevel.SOME -> R.string.information_some
        InformationLevel.MORE -> R.string.information_more
    }

    @StringRes
    fun reason(reason: PictureReason): Int = when (reason) {
        PictureReason.CONFIRMED_INCIDENT_TODAY -> R.string.picture_reason_incident_today
        PictureReason.CONFIRMED_INCIDENT_THIS_WEEK -> R.string.picture_reason_incident_week
        PictureReason.CONFIRMED_CONDITION -> R.string.picture_reason_condition
        PictureReason.CONDITIONS_MAY_HAVE_CHANGED -> R.string.picture_reason_may_have_changed
        PictureReason.CONFLICTING_REPORTS -> R.string.picture_reason_conflicting
        PictureReason.RECURRING_CONCERNS -> R.string.picture_reason_recurring
        PictureReason.UNCONFIRMED_REPORTS -> R.string.picture_reason_unconfirmed
        PictureReason.SAFE_PLACES_NEARBY -> R.string.picture_reason_safe_places
        PictureReason.LITTLE_INFORMATION -> R.string.picture_reason_little_information
    }

    @StringRes
    fun compass(compass: Compass): Int = when (compass) {
        Compass.N -> R.string.compass_n
        Compass.NE -> R.string.compass_ne
        Compass.E -> R.string.compass_e
        Compass.SE -> R.string.compass_se
        Compass.S -> R.string.compass_s
        Compass.SW -> R.string.compass_sw
        Compass.W -> R.string.compass_w
        Compass.NW -> R.string.compass_nw
    }

    /**
     * The reason lines worth reading, in order. CONFIRMED_CONDITION is said by the "Confirmed: …" line and
     * LITTLE_INFORMATION by the "How much is known" line, so they are not repeated.
     */
    fun shownReasons(picture: AreaPicture): List<PictureReason> =
        picture.reasons.filter { it != PictureReason.CONFIRMED_CONDITION && it != PictureReason.LITTLE_INFORMATION }

    /** Everything under the headline: how much is known, what is confirmed, why, and where the strongest concern is. */
    fun details(context: Context, picture: AreaPicture, origin: GeoPoint, night: Boolean): List<String> {
        val lines = mutableListOf(context.getString(R.string.picture_information, context.getString(information(picture.information))))
        val categories = IntelligencePolicy.orderForTime(picture.concernCategories, night)
        if (categories.isNotEmpty()) {
            lines += context.getString(R.string.picture_confirmed,
                categories.joinToString(" · ") { context.getString(CommunityTexts.category(it)) })
        }
        shownReasons(picture).forEach { lines += context.getString(reason(it)) }
        picture.highestConcernCell?.let { IntelligencePolicy.place(origin, it) }?.let { placement ->
            lines += if (placement.compass == null) {
                context.getString(R.string.picture_where_here)
            } else {
                context.getString(R.string.picture_where, context.getString(compass(placement.compass)),
                    CommunityTexts.distanceText(context, placement.approximateMeters.toDouble()))
            }
        }
        if (night && categories.any { it in IntelligencePolicy.NIGHT_RELEVANT } && categories.size > 1) {
            lines += context.getString(R.string.picture_night_first)
        }
        return lines
    }

    // ---- Phase 8D: "Check the way" -----------------------------------------------------------------------------

    @StringRes
    fun coverage(coverage: CorridorBriefPolicy.Coverage): Int = when (coverage) {
        CorridorBriefPolicy.Coverage.MOSTLY_UNKNOWN -> R.string.way_coverage_mostly_unknown
        CorridorBriefPolicy.Coverage.PARTLY_KNOWN -> R.string.way_coverage_partly_known
        CorridorBriefPolicy.Coverage.MOSTLY_KNOWN -> R.string.way_coverage_mostly_known
    }

    /** The brief, line by line: confirmed concerns first, then what is uncertain, then help nearby. */
    fun wayLines(context: Context, brief: CorridorBriefPolicy.CorridorBrief, found: String?): List<String> {
        val lines = mutableListOf<String>()
        lines += if (brief.hasConfirmedConcerns) {
            context.getString(R.string.way_concerns,
                brief.concernCategories.joinToString(" · ") { context.getString(CommunityTexts.category(it)) })
        } else {
            context.getString(R.string.way_no_concerns)
        }
        if (brief.incidentToday) lines += context.getString(R.string.way_incident_today)
        lines += context.getString(coverage(brief.coverage))
        if (brief.unconfirmedNearby) lines += context.getString(R.string.way_unconfirmed)
        if (brief.conflicting) lines += context.getString(R.string.way_conflicting)
        if (brief.recurringNearby) lines += context.getString(R.string.way_recurring)
        val havens = when (brief.havens) {
            CorridorBriefPolicy.Havens.NONE -> 0
            CorridorBriefPolicy.Havens.ONE -> 1
            CorridorBriefPolicy.Havens.SEVERAL -> 2
        }
        if (havens > 0) lines += context.resources.getQuantityString(R.plurals.way_havens, havens)
        found?.takeIf { it.isNotBlank() }?.let { lines += context.getString(R.string.way_found, it) }
        return lines
    }

    /** One message for every state that is not a brief; null for Idle / Ready. */
    @StringRes
    fun wayMessage(state: WayCheckState): Int? = when (state) {
        WayCheckState.Checking -> R.string.way_checking
        WayCheckState.NeedsDestination -> R.string.way_needs_destination
        WayCheckState.DestinationNotFound -> R.string.way_not_found
        WayCheckState.DestinationUnavailable -> R.string.way_unavailable
        is WayCheckState.NoLocation ->
            if (state.reason == LocationUnavailableReason.PERMISSION_DENIED) R.string.way_no_permission else R.string.way_no_fix
        WayCheckState.TooLong -> R.string.way_too_long
        WayCheckState.Incomplete -> R.string.way_incomplete
        WayCheckState.Idle, is WayCheckState.Ready, is WayCheckState.Failed -> null
    }
}
