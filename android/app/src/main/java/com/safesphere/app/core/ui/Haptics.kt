package com.safesphere.app.core.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * Phase 13F: the one haptic of Kurinji's moments (the butterfly saved, the stamp on share). Uses the view's own
 * feedback, so it follows the phone's touch-feedback setting and needs no VIBRATE permission.
 */
object Haptics {
    fun confirm(view: View) {
        val constant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
        else HapticFeedbackConstants.KEYBOARD_TAP
        view.performHapticFeedback(constant)
    }
}
