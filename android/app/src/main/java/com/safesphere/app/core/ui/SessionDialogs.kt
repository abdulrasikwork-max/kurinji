package com.safesphere.app.core.ui

import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.safesphere.app.R

/**
 * The Phase 2 "Session expired" behaviour, shared by every screen that calls the backend:
 * tell the person, then return to Login. By the time this shows, the repository has already cleared
 * the saved session.
 */
fun Fragment.showSessionExpiredDialog() {
    MaterialAlertDialogBuilder(requireContext())
        .setTitle(R.string.session_expired_title)
        .setMessage(R.string.error_session_expired)
        .setCancelable(false)
        .setPositiveButton(R.string.action_got_it) { _, _ ->
            findNavController().navigate(R.id.action_global_to_login)
        }
        .show()
}
