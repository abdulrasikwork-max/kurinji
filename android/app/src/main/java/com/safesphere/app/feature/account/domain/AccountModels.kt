package com.safesphere.app.feature.account.domain

import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.auth.domain.AuthUser
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.Flow

/** Phase 10A: password change and account deletion. Every call needs the account password; the server checks it. */
interface AccountRepository {
    /** On success the NEW token is already saved on this phone (every other session of the account has ended). */
    suspend fun changePassword(currentPassword: String, newPassword: String): ApiResult<Unit>

    /** Returns when, at the earliest, the account will be deleted; the session shows the pending deletion at once. */
    suspend fun requestDeletion(password: String): ApiResult<Instant>

    suspend fun cancelDeletion(password: String): ApiResult<Unit>

    /** Phase 10A.4: replaces the saved token with a fresh one for the same sign-in (only if it is still the saved one). */
    suspend fun renewToken(): ApiResult<Unit>
}

/**
 * Phase 10A.4: when the phone renews its 24 h token. Pure: the token's own "exp" claim (read on the phone, nothing is
 * verified here; the server checks every token) and the clock.
 */
object TokenRenewalPolicy {
    /** Renew when less than this is left. 12 h: a token is renewed about twice a day while the app runs. */
    val RENEW_WHEN_LEFT: java.time.Duration = java.time.Duration.ofHours(12)

    /** How often to look: often while an SOS or Safe Walk runs (it must never run out), rarely otherwise. */
    const val CHECK_EVERY_DURING_SAFETY_MILLIS = 10 * 60_000L
    const val CHECK_EVERY_OTHERWISE_MILLIS = 60 * 60_000L

    /** False when the expiry cannot be read (never renew in a loop over a token the phone cannot understand). */
    fun due(expiresAt: Instant?, now: Instant): Boolean =
        expiresAt != null && expiresAt.isAfter(now) && Duration.between(now, expiresAt) < RENEW_WHEN_LEFT

    /** The "exp" claim of a JWT (epoch seconds), or null when it cannot be read. */
    fun expiresAt(token: String): Instant? = try {
        val payload = token.split('.').getOrNull(1) ?: return null
        val json = String(java.util.Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)
        EXP.find(json)?.groupValues?.get(1)?.toLong()?.let(Instant::ofEpochSecond)
    } catch (e: IllegalArgumentException) {
        null
    }

    private val EXP = Regex("\"exp\"\\s*:\\s*(\\d+)")
}

/** What is wrong with a new password, checked on the phone before anything is sent (the server checks again). */
enum class PasswordProblem { CURRENT_MISSING, TOO_SHORT, TOO_LONG, MISMATCH, SAME_AS_CURRENT }

/** The banner while a deletion is pending: a date in the future, or "soon" once the date has passed (postponed). */
sealed interface DeletionNotice {
    data class Scheduled(val dueAt: Instant, val requestedAt: Instant?) : DeletionNotice
    data object Soon : DeletionNotice
}

/** Pure rules of the account screen (unit-tested). */
object AccountPolicy {
    const val MIN_PASSWORD_LENGTH = 8
    const val MAX_PASSWORD_BYTES = 72

    fun checkNewPassword(current: String, new: String, confirm: String): PasswordProblem? = when {
        current.isEmpty() -> PasswordProblem.CURRENT_MISSING
        new.length < MIN_PASSWORD_LENGTH -> PasswordProblem.TOO_SHORT
        new.toByteArray(StandardCharsets.UTF_8).size > MAX_PASSWORD_BYTES -> PasswordProblem.TOO_LONG
        new != confirm -> PasswordProblem.MISMATCH
        new == current -> PasswordProblem.SAME_AS_CURRENT
        else -> null
    }

    fun deletionNotice(user: AuthUser?, now: Instant): DeletionNotice? {
        val due = user?.deletionDueAt ?: return null
        return if (due.isAfter(now)) DeletionNotice.Scheduled(due, user.deletionRequestedAt) else DeletionNotice.Soon
    }
}

/**
 * A pending deletion this phone knows about, kept apart from the session (a sign-out does not forget it): after the
 * account is gone, the Login screen can offer to remove the account's data from this phone.
 */
data class PendingDeletion(val userId: Long, val dueAt: Instant)

interface PendingDeletionStore {
    val pending: Flow<PendingDeletion?>
    suspend fun set(value: PendingDeletion)

    /** Forgets the marker, but only if it is [userId]'s (null = whichever is stored). */
    suspend fun clear(userId: Long? = null)
}
