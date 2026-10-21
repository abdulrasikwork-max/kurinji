package com.safesphere.app.core.ui

import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.safesphere.app.R

fun Fragment.showComingLaterDialog(@StringRes featureTitleRes: Int) {
    MaterialAlertDialogBuilder(requireContext())
        .setTitle(getString(R.string.dialog_coming_title, getString(featureTitleRes)))
        .setMessage(R.string.dialog_coming_message)
        .setPositiveButton(R.string.action_got_it, null)
        .show()
}
