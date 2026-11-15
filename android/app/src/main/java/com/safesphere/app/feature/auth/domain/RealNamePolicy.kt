package com.safesphere.app.feature.auth.domain

import java.text.Normalizer

/**
 * Phase N2 (decision N-3): the phone-side copy of the server's real-name rule (backend RealNamePolicy; the same cases
 * in RealNamePolicyTest on both sides). The server checks again and decides.
 *
 * Cleaned: Unicode NFC, spaces around removed, runs of white space become one space. 2 to 60 characters with at least
 * 2 letters; letters of any script with the marks that complete them (Tamil vowel signs and pulli are marks), the
 * zero-width joiners Indic scripts use, spaces and . ' - (also ’). No digits, emoji, symbols or other punctuation.
 */
object RealNamePolicy {

    const val MIN = 2
    const val MAX = 60

    enum class Result { OK, TOO_SHORT, TOO_LONG, NOT_A_NAME }

    fun clean(raw: String?): String {
        if (raw == null) return ""
        return Normalizer.normalize(raw, Normalizer.Form.NFC).trim().replace(Regex("\\s+"), " ")
    }

    /** Checks an already [clean]ed name. */
    fun check(cleaned: String): Result {
        val length = cleaned.codePointCount(0, cleaned.length)
        if (length > MAX) return Result.TOO_LONG
        var letters = 0
        var i = 0
        while (i < cleaned.length) {
            val cp = cleaned.codePointAt(i)
            i += Character.charCount(cp)
            if (Character.isLetter(cp)) {
                letters++
            } else if (!allowedOther(cp)) {
                return Result.NOT_A_NAME
            }
        }
        if (length < MIN || letters < MIN) return Result.TOO_SHORT
        return Result.OK
    }

    private fun allowedOther(cp: Int): Boolean {
        val type = Character.getType(cp)
        return type == Character.NON_SPACING_MARK.toInt() || type == Character.COMBINING_SPACING_MARK.toInt() ||
            cp == ' '.code || cp == '.'.code || cp == '\''.code || cp == '-'.code || cp == 0x2019 ||
            cp == 0x200C || cp == 0x200D
    }
}
