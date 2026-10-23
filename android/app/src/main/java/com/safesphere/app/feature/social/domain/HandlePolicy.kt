package com.safesphere.app.feature.social.domain

import java.util.Locale

/**
 * Phase 13A: handle rules, the same as the backend HandlePolicy and the V20 CHECK. Lower-case ASCII letters, digits,
 * "_" and "."; 3 to 20 characters; starts with a letter; no "..", no "." at the end. ASCII only, so a handle cannot
 * imitate another with look-alike letters. The phone checks first for quick feedback; the server decides.
 */
object HandlePolicy {

    private val FORMAT = Regex("^[a-z][a-z0-9_.]{1,18}[a-z0-9_]$")

    private val RESERVED = setOf(
        "admin", "administrator", "moderator", "mod", "support", "help", "official", "team", "staff", "security",
        "safesphere", "kurinji", "system", "root", "null", "undefined", "me", "you", "anonymous", "deleted",
        "police", "emergency", "sos"
    )

    enum class Result { OK, INVALID_FORMAT, RESERVED }

    /** Trimmed and lower-cased; a leading "@" is dropped. */
    fun normalize(raw: String): String = raw.trim().removePrefix("@").lowercase(Locale.ROOT)

    fun check(normalized: String): Result {
        if (!FORMAT.matches(normalized) || normalized.contains("..")) return Result.INVALID_FORMAT
        val core = normalized.replace(Regex("[0-9_.]"), "")
        return if (normalized in RESERVED || core in RESERVED) Result.RESERVED else Result.OK
    }
}
