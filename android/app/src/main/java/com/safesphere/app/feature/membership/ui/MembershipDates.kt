package com.safesphere.app.feature.membership.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Phase N3: how membership dates are shown. Pure, so the "31 July, not 1 August" rule is tested. */
object MembershipDates {

    private val INDIA: ZoneId = ZoneId.of("Asia/Kolkata")

    /**
     * A membership ends at the END of 31 July of the graduation year, India time (the server stores 18:30 UTC, which
     * is midnight in India): the last day it works is the India date one second before that moment, whatever zone
     * the phone is set to.
     */
    fun lastDayOf(endsAt: Instant): LocalDate = endsAt.minusSeconds(1).atZone(INDIA).toLocalDate()

    fun lastDay(endsAt: Instant): String =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).format(lastDayOf(endsAt))
}
