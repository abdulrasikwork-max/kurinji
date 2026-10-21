package com.safesphere.app.core.ui

import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.safesphere.app.R
import com.safesphere.app.appContainer
import com.safesphere.app.core.network.SessionEndReason

/**
 * The Phase 2 "Session expired" behaviour, shared by every screen that calls the backend:
 * tell the person, then return to Login. By the time this shows, the repository has already cleared
 * the saved session. Phase 10A: the words follow the server's reason (password changed, account deleted).
 */
fun Fragment.showSessionExpiredDialog() {
    val (title, message) = sessionEndTexts(appContainer.sessionEnder.lastReason.value)
    MaterialAlertDialogBuilder(requireContext())
        .setTitle(title)
        .setMessage(message)
        .setCancelable(false)
        .setPositiveButton(R.string.action_got_it) { _, _ ->
            findNavController().navigate(R.id.action_global_to_login)
        }
        .show()
}

/** Title and message for why this phone was signed out (pure; tested in SessionEndTextsTest). */
fun sessionEndTexts(reason: SessionEndReason?): Pair<Int, Int> = when (reason) {
    SessionEndReason.CREDENTIALS_CHANGED -> R.string.session_password_changed_title to R.string.session_password_changed
    SessionEndReason.ACCOUNT_DELETED -> R.string.session_account_deleted_title to R.string.session_account_deleted
    SessionEndReason.ACCOUNT_UNAVAILABLE -> R.string.session_expired_title to R.string.session_account_unavailable
    SessionEndReason.EXPIRED, null -> R.string.session_expired_title to R.string.error_session_expired
}
