package com.safesphere.app.core.ui

import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.safesphere.app.R
import com.safesphere.app.appContainer
import com.safesphere.app.core.network.SessionEndReason

/**
 * The Phase 2 "Session expired" behaviour, shared by every screen that calls the backend. Phase 10A: the words follow
 * the server's reason (password changed, account deleted).
 *
 * Phase 11 C1: an expired token that could not be refreshed, or this phone's sign-in ended on the server, is NOT a
 * sign-out: nothing was deleted and SOS keeps working. Then the dialog says "Sign in to sync" and can be closed
 * ("Later"); the person is not pushed to Login in the middle of whatever they were doing. Only a changed password, a
 * deleted or a disabled account (the session was cleared) keep the old forced dialog that returns to Login.
 */
fun Fragment.showSessionExpiredDialog() {
    val reason = appContainer.sessionEnder.lastReason.value
    // Found on the emulator: decide by whether a session is still on this phone, not by the last reason alone (a
    // signed-out phone must get the old forced dialog even when the last recorded reason was an expiry).
    if (forcedSignOutDialog(reason, appContainer.currentSession.value != null)) {
        val (title, message) = sessionEndTexts(reason)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(R.string.action_got_it) { _, _ ->
                findNavController().navigate(R.id.action_global_to_login)
            }
            .show()
    } else {
        val (title, message) = signInToSyncTexts(reason)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(R.string.action_sign_in) { _, _ ->
                findNavController().navigate(R.id.action_global_to_login)
            }
            .setNegativeButton(R.string.action_later, null)
            .show()
    }
}

/** The forced "signed out" dialog (back to Login): a revocation, or no session left on this phone. Pure; tested. */
fun forcedSignOutDialog(reason: SessionEndReason?, sessionOnPhone: Boolean): Boolean = signsOut(reason) || !sessionOnPhone

/** True for the reasons that clear the session on this phone (Phase 10A security events). Pure; tested. */
fun signsOut(reason: SessionEndReason?): Boolean = when (reason) {
    SessionEndReason.CREDENTIALS_CHANGED, SessionEndReason.ACCOUNT_DELETED, SessionEndReason.ACCOUNT_UNAVAILABLE -> true
    SessionEndReason.EXPIRED, SessionEndReason.SESSION_REVOKED, null -> false
}

/** Title and message for why this phone was signed out (pure; tested in SessionEndTextsTest). */
fun sessionEndTexts(reason: SessionEndReason?): Pair<Int, Int> = when (reason) {
    SessionEndReason.CREDENTIALS_CHANGED -> R.string.session_password_changed_title to R.string.session_password_changed
    SessionEndReason.ACCOUNT_DELETED -> R.string.session_account_deleted_title to R.string.session_account_deleted
    SessionEndReason.ACCOUNT_UNAVAILABLE -> R.string.session_expired_title to R.string.session_account_unavailable
    SessionEndReason.EXPIRED, SessionEndReason.SESSION_REVOKED, null ->
        R.string.session_expired_title to R.string.error_session_expired
}

/** Phase 11 C1: title and message of the "Sign in to sync" dialog (pure; tested). */
fun signInToSyncTexts(reason: SessionEndReason?): Pair<Int, Int> = when (reason) {
    SessionEndReason.SESSION_REVOKED -> R.string.session_sign_in_to_sync_title to R.string.session_sign_in_to_sync_revoked
    else -> R.string.session_sign_in_to_sync_title to R.string.session_sign_in_to_sync
}
