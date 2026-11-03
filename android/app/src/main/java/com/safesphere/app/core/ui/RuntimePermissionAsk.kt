package com.safesphere.app.core.ui

/**
 * Phase 17H (found on the Galaxy A23): one rule for a runtime permission that is not location (location has
 * `LocationAsk`): when must a button take the person to Android's settings instead of asking again?
 *
 * Android answers `shouldShowRequestPermissionRationale = false` BOTH before the first question and after "Don't allow"
 * was answered twice, so "it is false" alone proves nothing. Only a screen that has itself asked ([asked]) and now sees
 * no rationale knows the dialog will not come back: asking again would do nothing, so the button must say
 * "Open settings" AND open them. Label and action both come from here, so they cannot disagree.
 */
object RuntimePermissionAsk {

    fun needsSettings(asked: Boolean, shouldShowRationale: Boolean): Boolean = asked && !shouldShowRationale
}
