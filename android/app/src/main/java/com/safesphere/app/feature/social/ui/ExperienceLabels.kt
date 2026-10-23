package com.safesphere.app.feature.social.ui

import androidx.annotation.StringRes
import com.safesphere.app.R
import com.safesphere.app.feature.social.domain.Audience
import com.safesphere.app.feature.social.domain.City
import com.safesphere.app.feature.social.domain.CommentKind
import com.safesphere.app.feature.social.domain.CommentPolicy
import com.safesphere.app.feature.social.domain.ComposerIssue
import com.safesphere.app.feature.social.domain.Daypart
import com.safesphere.app.feature.social.domain.Precision
import com.safesphere.app.feature.social.domain.PrivatePlaceKind
import com.safesphere.app.feature.social.domain.TimeBand
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/** Phase 13B: time bands as words. A band is all anyone but the author ever learns about when something was shared. */
object TimeBandTexts {
    @StringRes
    fun of(band: TimeBand): Int = when (band) {
        TimeBand.THIS_WEEK -> R.string.social_band_this_week
        TimeBand.THIS_MONTH -> R.string.social_band_this_month
        TimeBand.EARLIER -> R.string.social_band_earlier
    }
}

/** Phase 13B: the words for the experience codes. */
object ExperienceLabels {

    @StringRes
    fun of(kind: CommentKind): Int = when (kind) {
        CommentKind.BEEN_HERE -> R.string.social_kind_been_here
        CommentKind.QUESTION -> R.string.social_kind_question
        CommentKind.REPLY -> R.string.social_kind_reply
        CommentKind.ANSWER -> R.string.social_kind_answer
    }

    @StringRes
    fun of(audience: Audience): Int = when (audience) {
        Audience.EVERYONE -> R.string.social_audience_everyone
        Audience.CONNECTIONS -> R.string.social_audience_connections
        Audience.ONLY_ME -> R.string.social_audience_only_me
    }

    /** The one plain sentence shown with each audience (design §4.1). */
    @StringRes
    fun lineOf(audience: Audience): Int = when (audience) {
        Audience.EVERYONE -> R.string.social_audience_everyone_line
        Audience.CONNECTIONS -> R.string.social_audience_connections_line
        Audience.ONLY_ME -> R.string.social_audience_only_me_line
    }

    @StringRes
    fun of(policy: CommentPolicy): Int = when (policy) {
        CommentPolicy.EVERYONE -> R.string.social_comments_everyone
        CommentPolicy.CONNECTIONS -> R.string.social_comments_connections
        CommentPolicy.NOBODY -> R.string.social_comments_nobody
    }

    @StringRes
    fun of(daypart: Daypart): Int = when (daypart) {
        Daypart.MORNING -> R.string.social_daypart_morning
        Daypart.AFTERNOON -> R.string.social_daypart_afternoon
        Daypart.EVENING -> R.string.social_daypart_evening
        Daypart.NIGHT -> R.string.social_daypart_night
    }

    @StringRes
    fun of(precision: Precision): Int = when (precision) {
        Precision.EXACT -> R.string.social_precision_exact
        Precision.AREA -> R.string.social_precision_area
        Precision.CITY -> R.string.social_precision_city
        Precision.NONE -> R.string.social_precision_none
    }

    @StringRes
    fun of(kind: PrivatePlaceKind): Int = when (kind) {
        PrivatePlaceKind.HOME -> R.string.social_private_kind_home
        PrivatePlaceKind.HOSTEL -> R.string.social_private_kind_hostel
        PrivatePlaceKind.WORK -> R.string.social_private_kind_work
        PrivatePlaceKind.OTHER -> R.string.social_private_kind_other
    }

    @StringRes
    fun of(issue: ComposerIssue): Int = when (issue) {
        ComposerIssue.BODY_EMPTY -> R.string.social_issue_body_empty
        ComposerIssue.BODY_TOO_LONG -> R.string.social_issue_body_too_long
        ComposerIssue.ASK_TOO_SHORT -> R.string.social_issue_ask_too_short
        ComposerIssue.ASK_TOO_LONG -> R.string.social_issue_ask_too_long
        ComposerIssue.TIP_TOO_LONG -> R.string.social_issue_tip_too_long
        ComposerIssue.NO_MONTH -> R.string.social_issue_no_month
        ComposerIssue.MONTH_IN_FUTURE -> R.string.social_issue_month_future
        ComposerIssue.NO_PRECISION -> R.string.social_issue_no_precision
        ComposerIssue.NO_PLACE -> R.string.social_issue_no_place
        ComposerIssue.NO_CITY -> R.string.social_issue_no_city
        ComposerIssue.PRIVATE_PLACE -> R.string.social_issue_private_place
        ComposerIssue.NO_AUDIENCE -> R.string.social_issue_no_audience
        ComposerIssue.TOO_MANY_PHOTOS -> R.string.social_issue_too_many_photos
    }

    /** City names are proper names, the same in every language of the UI ("TIRUCHIRAPPALLI" -> "Tiruchirappalli"). */
    fun cityName(city: City): String = city.name.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }

    fun monthName(month: YearMonth, locale: Locale = Locale.getDefault()): String =
        month.month.getDisplayName(TextStyle.FULL, locale) + " " + month.year
}
