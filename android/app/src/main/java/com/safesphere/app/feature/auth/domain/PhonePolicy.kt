package com.safesphere.app.feature.auth.domain

/**
 * Phase 17B: the phone-side copy of the server's number rule (backend PhoneNumbers; the same cases in PhonePolicyTest).
 * India only for now: a 10-digit mobile number starting with 6-9, typed with or without +91 / 91 / 0, spaces, dashes
 * or brackets. The server checks again and decides.
 */
object PhonePolicy {

    private val MOBILE = Regex("^[6-9][0-9]{9}$")
    private val SEPARATORS = Regex("[\\s().-]")

    /** "+91XXXXXXXXXX", or null when [raw] is not an Indian mobile number. */
    fun normalize(raw: String?): String? {
        if (raw == null) return null
        var digits = raw.trim().replace(SEPARATORS, "")
        digits = when {
            digits.startsWith("+91") -> digits.removePrefix("+91")
            digits.startsWith("+") -> return null
            digits.length == 12 && digits.startsWith("91") -> digits.substring(2)
            digits.length == 11 && digits.startsWith("0") -> digits.substring(1)
            else -> digits
        }
        return if (MOBILE.matches(digits)) "+91$digits" else null
    }

    fun isValid(raw: String?): Boolean = normalize(raw) != null

    /** A code as typed (spaces allowed) is ready to send when it is exactly 6 digits. */
    fun cleanCode(raw: String?): String = raw.orEmpty().filter { it.isDigit() }.take(CODE_LENGTH)

    const val CODE_LENGTH = 6
}
