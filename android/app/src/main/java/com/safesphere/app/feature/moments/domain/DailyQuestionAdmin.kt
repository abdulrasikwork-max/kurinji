package com.safesphere.app.feature.moments.domain

import com.safesphere.app.core.network.ApiResult
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Phase N4 (A3, D8 / N-49 / N-66): the daily question as admins schedule it. One per India calendar day, today to 60
 * days ahead, 3 to 140 characters on one line, no editing (delete and write again); deleting keeps the answers as plain
 * moments. The server checks the role and every rule again.
 */
data class ScheduledQuestion(val id: String, val day: LocalDate, val text: String)

interface QuestionAdminRepository {
    /** From 7 days ago onwards, oldest first. */
    suspend fun list(): ApiResult<List<ScheduledQuestion>>

    /** CONFLICT with the server's words when that day already has one. */
    suspend fun schedule(day: LocalDate, text: String): ApiResult<ScheduledQuestion>

    suspend fun delete(id: String): ApiResult<Unit>
}

/** The server's rules for a question, mirrored so the admin learns them while writing (PURE, tested). */
object QuestionRules {
    const val MIN = 3
    const val MAX = 140
    const val DAYS_AHEAD = 60L

    /** The days are India calendar days (the question of the day is pinned by India's date). */
    val INDIA: ZoneId = ZoneId.of("Asia/Kolkata")

    fun today(now: Instant): LocalDate = now.atZone(INDIA).toLocalDate()

    fun dayAllowed(day: LocalDate, now: Instant): Boolean {
        val today = today(now)
        return !day.isBefore(today) && !day.isAfter(today.plusDays(DAYS_AHEAD))
    }

    fun textFits(text: String): Boolean {
        val trimmed = text.trim()
        val length = trimmed.codePointCount(0, trimmed.length)
        return length in MIN..MAX && trimmed.none { it == '\n' || it == '\r' }
    }

    /** A question already scheduled for [day] (the server answers 409 for a second one). */
    fun taken(day: LocalDate, scheduled: List<ScheduledQuestion>): Boolean = scheduled.any { it.day == day }
}
