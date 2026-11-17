package com.safesphere.app.feature.social.domain

/**
 * Phase 13A: quick checks of the profile form on the phone, mirroring the backend (HandlePolicy, ProfileTextPolicy).
 * Lengths count characters (code points), so Tamil text is measured the way the server measures it. The server cleans
 * the text again (invisible characters, spaces) and decides.
 */
object ProfileInputPolicy {

    const val BIO_MAX = 160

    private val LINK = Regex(
        "(?i)(https?://|www\\.|\\b[a-z0-9-]+\\.(com|in|net|org|io|me|app|co|xyz|link|ly|info|biz|site|online|top)\\b)"
    )

    enum class Problem {
        HANDLE_FORMAT, HANDLE_UNAVAILABLE, BIO_TOO_LONG, LINK
    }

    data class Problems(val handle: Problem? = null, val bio: Problem? = null) {
        val any: Boolean get() = handle != null || bio != null
    }

    fun check(input: ProfileInput): Problems {
        val handle = when (HandlePolicy.check(HandlePolicy.normalize(input.handle))) {
            HandlePolicy.Result.OK -> null
            HandlePolicy.Result.INVALID_FORMAT -> Problem.HANDLE_FORMAT
            HandlePolicy.Result.RESERVED -> Problem.HANDLE_UNAVAILABLE
        }
        val bioText = input.bio.trim()
        val bio = when {
            length(bioText) > BIO_MAX -> Problem.BIO_TOO_LONG
            LINK.containsMatchIn(bioText) -> Problem.LINK
            else -> null
        }
        return Problems(handle, bio)
    }

    private fun length(text: String): Int = text.codePointCount(0, text.length)
}
