package com.safesphere.app.core.textcheck

/** Phase 13E: the words a flagged span covers (code points of the text that was checked). Pure. */
object TextSpans {

    private const val MAX_PHRASE = 60

    /** The words of [span] in [text], trimmed and at most 60 characters; null for a span outside the text. */
    fun phrase(text: String, span: IntRange): String? {
        val count = text.codePointCount(0, text.length)
        if (span.isEmpty() || span.first < 0 || span.last >= count) return null
        val words = text.substring(text.offsetByCodePoints(0, span.first), text.offsetByCodePoints(0, span.last + 1))
            .trim()
        if (words.isEmpty()) return null
        return if (words.codePointCount(0, words.length) > MAX_PHRASE) {
            words.substring(0, words.offsetByCodePoints(0, MAX_PHRASE)) + "…"
        } else words
    }

    /** Up to three distinct phrases, joined as “a”, “b” (the caller adds the outer quotation marks). */
    fun phrases(text: String, spans: List<IntRange>): String =
        spans.take(3).mapNotNull { phrase(text, it) }.distinct().joinToString("”, “")
}
