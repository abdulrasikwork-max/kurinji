package com.safesphere.app.feature.social.ui

import android.animation.ValueAnimator
import android.view.LayoutInflater
import android.view.View
import android.view.animation.OvershootInterpolator
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.safesphere.app.R
import com.safesphere.app.core.ui.Haptics
import com.safesphere.app.core.ui.components.ButterflyDrawable
import com.safesphere.app.databinding.SheetBeenInvitationBinding

/**
 * Phase 13F, simplified in Phase 16: "I've been". The saved row bounces, then a sheet asks how it was (design §3.4.2):
 * a "Been here too" comment, a post of the person's own, or later.
 */
object BeenInvitation {

    /**
     * Phase 16: "I've been" on a saved row: the row's picture gives one small bounce (with a haptic), then [then] moves
     * the item. With animations off it simply moves.
     */
    fun bounce(view: View, then: () -> Unit) {
        Haptics.confirm(view)
        if (!ValueAnimator.areAnimatorsEnabled()) {
            then()
            return
        }
        view.animate().scaleX(1.12f).scaleY(1.12f).setDuration(140).withEndAction {
            view.animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(OvershootInterpolator(2f))
                .withEndAction { then() }.start()
        }.start()
    }
    fun show(fragment: Fragment, canComment: Boolean, onPerspective: () -> Unit, onOwn: () -> Unit) {
        val context = fragment.requireContext()
        val dialog = BottomSheetDialog(context)
        val sheet = SheetBeenInvitationBinding.inflate(LayoutInflater.from(context))
        sheet.glyph.setImageDrawable(ButterflyDrawable.intent(context, 56))
        sheet.perspectiveButton.isVisible = canComment
        sheet.perspectiveButton.setOnClickListener { dialog.dismiss(); onPerspective() }
        sheet.ownButton.setOnClickListener { dialog.dismiss(); onOwn() }
        sheet.laterButton.setOnClickListener { dialog.dismiss() }
        dialog.setContentView(sheet.root)
        dialog.show()
        sheet.root.announceForAccessibility(context.getString(R.string.been_invitation_title))
    }
}
