package com.safesphere.app.feature.membership.domain

import java.util.Locale

/**
 * Phase N3: pure mirrors of the server's `CommunityRules` (owner's item 1: no fixed roll-number format is assumed;
 * `E1236003` is only an example). Checked on the phone first so a typo is caught before anything is sent; the server
 * decides anyway.
 */
object RollNumberPolicy {

    private val ROLL = Regex("^(?=.*[0-9])[A-Z0-9]{4,15}$")

    /** Upper-case, without spaces and the separators people type (- / .); null for a blank input. */
    fun normalise(raw: String?): String? {
        if (raw == null) return null
        val out = raw.trim().uppercase(Locale.ROOT).replace(Regex("[\\s\\-/.]"), "")
        return out.ifEmpty { null }
    }

    /** True when [raw] can be a roll number (4–15 letters and digits, at least one digit, after normalising). */
    fun accepts(raw: String?): Boolean = normalise(raw)?.let(ROLL::matches) == true
}

/** The short codes of the server: 8 characters without 0/O, 1/I/L, shown as `ABCD-2345`. */
object ShortCode {
    private val SHOWN = Regex("^[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{4}-[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{4}$")

    /** The code as the server answered it, or null when it is not a well-formed code (fail closed: nothing shown). */
    fun clean(code: String?): String? = code?.trim()?.takeIf(SHOWN::matches)
}
