package com.safesphere.app.feature.social.domain

import java.time.Clock
import java.time.LocalDate
import java.time.Period
import java.time.ZoneOffset

/**
 * Phase 13A (decision D6): the app is for people aged 18 and over. Mirrors the backend AgePolicy exactly, including
 * "today" = the UTC date, so the phone and the server never disagree about the same birthday. The date of birth is
 * only checked: it is sent once with the sign-up (or the one-time confirmation) and is stored nowhere, here or there.
 */
object AgePolicy {

    const val MINIMUM_AGE = 18
    const val MAXIMUM_AGE = 120

    enum class Result { ADULT, UNDER_AGE, INVALID }

    fun today(clock: Clock = Clock.systemUTC()): LocalDate = LocalDate.now(clock.withZone(ZoneOffset.UTC))

    fun check(dateOfBirth: LocalDate?, today: LocalDate): Result {
        if (dateOfBirth == null || dateOfBirth.isAfter(today)) return Result.INVALID
        val years = Period.between(dateOfBirth, today).years
        if (years > MAXIMUM_AGE) return Result.INVALID
        return if (years >= MINIMUM_AGE) Result.ADULT else Result.UNDER_AGE
    }

    /** The latest date of birth that is 18+ today (where the date picker opens). */
    fun latestAdultBirthDate(today: LocalDate): LocalDate = today.minusYears(MINIMUM_AGE.toLong())
}
