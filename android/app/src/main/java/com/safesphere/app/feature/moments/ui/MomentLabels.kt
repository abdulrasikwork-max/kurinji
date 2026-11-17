package com.safesphere.app.feature.moments.ui

import androidx.annotation.StringRes
import com.safesphere.app.R
import com.safesphere.app.feature.moments.domain.DayPart
import com.safesphere.app.feature.moments.domain.MomentNotice
import com.safesphere.app.feature.moments.domain.NoticeState
import com.safesphere.app.feature.moments.domain.RoughDay
import com.safesphere.app.feature.moments.domain.RoughTime
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

    /** What the author is told. Every state has words; an unknown reason falls back to the plain sentence. */
    @StringRes
    fun notice(notice: MomentNotice): Int = when (notice.state) {
        NoticeState.HELD -> when (notice.reason) {
            "NEW_MEMBER_PHOTO" -> R.string.moment_notice_new_member_photo
            "AWAITING_CHECK" -> R.string.moment_notice_awaiting_check
            else -> R.string.moment_notice_held
        }
        NoticeState.COLLAPSED -> R.string.moment_notice_collapsed
        NoticeState.REMOVED -> R.string.moment_notice_removed
    }

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
