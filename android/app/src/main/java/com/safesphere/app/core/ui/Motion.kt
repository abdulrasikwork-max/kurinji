package com.safesphere.app.core.ui

import android.animation.ValueAnimator
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * The only entrance animation in the app: a short fade + small upward slide, one view after another.
 * It does nothing when the user has turned animations off in the system settings.
 */
object Motion {

    private const val DURATION_MS = 280L
    private const val STAGGER_MS = 45L
    private const val OFFSET_DP = 14f

    fun revealInSequence(views: List<View>) {
        if (views.isEmpty() || !ValueAnimator.areAnimatorsEnabled()) return
        val offsetPx = views.first().resources.displayMetrics.density * OFFSET_DP

        views.forEachIndexed { index, view ->
            view.alpha = 0f
            view.translationY = offsetPx
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(index * STAGGER_MS)
                .setDuration(DURATION_MS)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }
}
