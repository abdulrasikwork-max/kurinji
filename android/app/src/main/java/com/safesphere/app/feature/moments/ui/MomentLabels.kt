package com.safesphere.app.feature.moments.ui

import androidx.annotation.StringRes
import com.safesphere.app.R
import com.safesphere.app.feature.moments.domain.DayPart
import com.safesphere.app.feature.moments.domain.MomentNotice
import com.safesphere.app.feature.moments.domain.NoticeState
import com.safesphere.app.feature.moments.domain.RoughDay
import com.safesphere.app.feature.moments.domain.RoughTime
import com.safesphere.app.feature.social.domain.ReportReason
import com.safesphere.app.feature.social.ui.ModerationLabels
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Phase N4: the words for a moment. Pure (no Android classes besides resource ids), so the rules are unit tested:
 * other people only ever get a rough time; a plan's time is the one exact time shown; the author is always told the
 * state of their own moment in plain words, in the calm (amber) tone, never as an alarm.
 */
object MomentLabels {

    /** "This morning", "Yesterday evening", "This week" … never a clock time. */
    @StringRes
    fun shared(time: RoughTime): Int = when (time.day) {
        RoughDay.TODAY -> when (time.part) {
            DayPart.MORNING -> R.string.moment_when_today_morning
            DayPart.AFTERNOON -> R.string.moment_when_today_afternoon
            DayPart.EVENING -> R.string.moment_when_today_evening
            DayPart.NIGHT -> R.string.moment_when_today_night
            null -> R.string.moment_when_today
        }
        RoughDay.YESTERDAY -> when (time.part) {
            DayPart.MORNING -> R.string.moment_when_yesterday_morning
            DayPart.AFTERNOON -> R.string.moment_when_yesterday_afternoon
            DayPart.EVENING -> R.string.moment_when_yesterday_evening
            DayPart.NIGHT -> R.string.moment_when_yesterday_night
            null -> R.string.moment_when_yesterday
        }
        RoughDay.THIS_WEEK -> R.string.moment_when_this_week
        RoughDay.THIS_MONTH -> R.string.moment_when_this_month
        RoughDay.EARLIER -> R.string.moment_when_earlier
    }

    /**
     * What the author is told about their own moment (no hidden states). The automatic check speaks for itself ("Our
     * system thinks…"), never as a moderator; a moderator's decision names its reason; a moment someone asked to have
     * looked at reads as a plain review (the author never learns that someone asked, or who). The sentence takes the
     * reason's words ([reason]) as its first argument where it names one.
     */
    @StringRes
    fun notice(notice: MomentNotice): Int {
        val guideline = ReportReason.entries.any { it.name == notice.reason }
        return when (notice.state) {
            NoticeState.HELD -> when {
                notice.reason == "NEW_MEMBER_PHOTO" -> R.string.moment_notice_new_member_photo
                notice.reason == "AWAITING_CHECK" -> R.string.own_waiting_check
                guideline && notice.byPerson == false -> R.string.own_held_by_system
                guideline && notice.byPerson == true -> R.string.own_held
                else -> R.string.moment_notice_held
            }
            NoticeState.COLLAPSED -> when {
                guideline && notice.byPerson == false -> R.string.own_collapsed_by_system
                guideline && notice.byPerson == true -> R.string.own_collapsed_by_moderator
                else -> R.string.moment_notice_collapsed
            }
            NoticeState.REMOVED -> if (guideline) R.string.own_removed else R.string.moment_notice_removed
        }
    }

    /** The words for a notice's reason code (a guideline), or the plain "not said" words. */
    @StringRes
    fun reason(code: String?): Int =
        ReportReason.entries.firstOrNull { it.name == code }?.let { ModerationLabels.of(it) }
            ?: R.string.moderation_reason_unknown

    /** The day of a plan as the reader's phone sees it. */
    enum class PlanDay { TODAY, TOMORROW, OTHER }

    data class PlanTime(val day: PlanDay, val date: LocalDate, val time: LocalTime)

    /** A plan's exact time in the reader's own time zone, with the day in words when it is today or tomorrow. */
    fun planTime(at: Instant, now: Instant, zone: ZoneId): PlanTime {
        val local = at.atZone(zone)
        val today = now.atZone(zone).toLocalDate()
        val day = when (local.toLocalDate()) {
            today -> PlanDay.TODAY
            today.plusDays(1) -> PlanDay.TOMORROW
            else -> PlanDay.OTHER
        }
        return PlanTime(day, local.toLocalDate(), local.toLocalTime())
    }

    @StringRes
    fun planDay(day: PlanDay): Int = when (day) {
        PlanDay.TODAY -> R.string.moment_plan_today
        PlanDay.TOMORROW -> R.string.moment_plan_tomorrow
        PlanDay.OTHER -> R.string.moment_plan_on
    }
}
