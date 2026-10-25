package com.safesphere.app.feature.social.ui

/**
 * Phase 13F: the post page shows the first line as a headline in the display face when it reads like one (one line, not
 * too long, more text after it); otherwise the whole text is body. Nothing is cut: every word of the post is shown.
 */
object PostPageText {

    const val HEADLINE_MAX = 120

    /** (headline or null, the rest). The headline and the rest together are exactly the post's text. */
    fun split(body: String): Pair<String?, String> {
        val text = body.trim()
        val firstLine = text.substringBefore('\n').trim()
        val rest = text.substringAfter('\n', "").trim()
        return if (firstLine.isNotEmpty() && firstLine.length <= HEADLINE_MAX && rest.isNotEmpty()) firstLine to rest
        else if (firstLine.isNotEmpty() && firstLine.length <= HEADLINE_MAX && rest.isEmpty() && firstLine.length <= 60) firstLine to ""
        else null to text
    }
}
