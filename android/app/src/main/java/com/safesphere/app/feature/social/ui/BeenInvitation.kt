package com.safesphere.app.feature.social.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.LayoutInflater
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.safesphere.app.R
import com.safesphere.app.core.ui.Haptics
import com.safesphere.app.core.ui.components.ButterflyDrawable
import com.safesphere.app.databinding.SheetBeenInvitationBinding

/**
 * Phase 13F, the second moment: "I've been". The chrysalis splits and the butterfly opens its wings (~700 ms), then
 * a sheet asks what the next person should know (design §3.4.2): a "Been here too" perspective, an experience of the
 * person's own, or later. With animations off the glyph simply changes.
 */
object BeenInvitation {

    /** Lets the butterfly out of [glyph] (a chrysalis), then calls [then]. */
    fun emerge(glyph: ImageView, then: () -> Unit) {
        Haptics.confirm(glyph)
        if (!ValueAnimator.areAnimatorsEnabled()) {
            glyph.setImageDrawable(ButterflyDrawable.intent(glyph.context, 40))
            then()
            return
        }
        val split = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(glyph, View.SCALE_X, 1f, 1.15f, 0.2f),
                ObjectAnimator.ofFloat(glyph, View.ROTATION, 0f, -8f, 8f, 0f)
            )
            duration = 260
        }
        split.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                val butterfly = ButterflyDrawable.intent(glyph.context, 40)
                glyph.setImageDrawable(butterfly)
                glyph.scaleX = 0.4f
                glyph.scaleY = 0.4f
                glyph.animate().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(OvershootInterpolator(2f))
                    .withEndAction { then() }.start()
                ValueAnimator.ofFloat(1f, 0f, 1f, 0f).apply {
                    duration = 420
                    addUpdateListener { butterfly.flap = it.animatedValue as Float }
                }.start()
            }
        })
        split.start()
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
