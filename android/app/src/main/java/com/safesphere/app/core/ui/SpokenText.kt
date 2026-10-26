package com.safesphere.app.core.ui

/**
 * Phase 15: parts of a screen-reader sentence ("%1$s. %2$s.") often end in their own full stop already
 * ("Only you see this list."), which TalkBack then read as "list.." in a row. A part goes into such a sentence without
 * its closing full stops; the sentence adds exactly one.
 */
object SpokenText {

    fun part(text: CharSequence?): String = text?.toString().orEmpty().trim().trimEnd('.').trimEnd()
}
