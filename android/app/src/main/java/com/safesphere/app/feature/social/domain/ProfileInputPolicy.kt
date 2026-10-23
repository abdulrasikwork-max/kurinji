package com.safesphere.app.feature.social.domain

/**
 * Phase 13A: quick checks of the profile form on the phone, mirroring the backend (HandlePolicy, ProfileTextPolicy).
 * Lengths count characters (code points), so Tamil text is measured the way the server measures it. The server cleans
 * the text again (invisible characters, spaces) and decides.
 */
object ProfileInputPolicy {

    const val DISPLAY_NAME_MAX = 40
    const val BIO_MAX = 160
    const val CITY_MAX = 60

    private val LINK = Regex(
        "(?i)(https?://|www\\.|\\b[a-z0-9-]+\\.(com|in|net|org|io|me|app|co|xyz|link|ly|info|biz|site|online|top)\\b)"
    )
    private val CITY = Regex("^[\\p{L}\\p{M}][\\p{L}\\p{M} .'-]*$")

    enum class Problem {
        HANDLE_FORMAT, HANDLE_UNAVAILABLE, NAME_REQUIRED, NAME_TOO_LONG, BIO_TOO_LONG, CITY_INVALID, LINK
    }

    data class Problems(
        val handle: Problem? = null,
        val displayName: Problem? = null,
        val bio: Problem? = null,
        val city: Problem? = null
    ) {
        val any: Boolean get() = handle != null || displayName != null || bio != null || city != null
    }

    fun check(input: ProfileInput): Problems {
        val handle = when (HandlePolicy.check(HandlePolicy.normalize(input.handle))) {
            HandlePolicy.Result.OK -> null
            HandlePolicy.Result.INVALID_FORMAT -> Problem.HANDLE_FORMAT
            HandlePolicy.Result.RESERVED -> Problem.HANDLE_UNAVAILABLE
        }
        val name = input.displayName.trim()
        val displayName = when {
            name.isEmpty() -> Problem.NAME_REQUIRED
            length(name) > DISPLAY_NAME_MAX -> Problem.NAME_TOO_LONG
            LINK.containsMatchIn(name) -> Problem.LINK
            else -> null
        }
        val bioText = input.bio.trim()
        val bio = when {
            length(bioText) > BIO_MAX -> Problem.BIO_TOO_LONG
            LINK.containsMatchIn(bioText) -> Problem.LINK
            else -> null
        }
        val cityText = input.city.trim().replace(Regex("\\s+"), " ")
        val city = if (cityText.isEmpty() || (length(cityText) <= CITY_MAX && CITY.matches(cityText))) null
        else Problem.CITY_INVALID
        return Problems(handle, displayName, bio, city)
    }

    private fun length(text: String): Int = text.codePointCount(0, text.length)
}
