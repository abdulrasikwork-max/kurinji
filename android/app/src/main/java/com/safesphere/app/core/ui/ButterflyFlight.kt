package com.safesphere.app.core.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import com.safesphere.app.core.ui.components.ButterflyDrawable
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/**
 * Phase 13F, the first of Kurinji's four moments: saving to the Cocoon. Two wing beats, then a curved flight from the
 * button into the Cocoon tab, which pulses once (~600 ms). Drawn in the window's overlay, so no layout changes.
 * Without animations (system setting) or without a visible target, it only calls [onLanded]: the caller then changes the
 * button's state and TalkBack announces it, so nobody depends on the motion.
 */
object ButterflyFlight {

    private const val DURATION_MS = 620L

    fun fly(source: View, target: View?, onLanded: () -> Unit) {
        val root = source.rootView as? ViewGroup
        if (root == null || target == null || !target.isShown || !ValueAnimator.areAnimatorsEnabled()) {
            onLanded()
            return
        }
        val density = source.resources.displayMetrics.density
        val start = centreIn(root, source)
        val end = centreIn(root, target)
        val lift = 96f * density
        val controlX = start.first + (end.first - start.first) * 0.2f
        val controlY = min(start.second, end.second) - lift
        val drawable = ButterflyDrawable.intent(source.context, 30)
        val w = drawable.intrinsicWidth
        val h = drawable.intrinsicHeight
        root.overlay.add(drawable)

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DURATION_MS
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener {
                val t = it.animatedValue as Float
                val u = 1f - t
                // Quadratic Bezier from the button, up and over, into the tab.
                val x = u * u * start.first + 2 * u * t * controlX + t * t * end.first
                val y = u * u * start.second + 2 * u * t * controlY + t * t * end.second
                val scale = if (t < 0.3f) 1f + t / 0.3f * 0.15f else 1.15f - (t - 0.3f) / 0.7f * 0.6f
                drawable.flap = abs(sin(t * Math.PI.toFloat() * 4f))
                drawable.alpha = (255 * (1f - (t - 0.75f).coerceAtLeast(0f) * 2.4f)).toInt().coerceIn(40, 255)
                val hw = w * scale / 2f
                val hh = h * scale / 2f
                drawable.setBounds((x - hw).toInt(), (y - hh).toInt(), (x + hw).toInt(), (y + hh).toInt())
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    root.overlay.remove(drawable)
                    pulse(target)
                    onLanded()
                }
            })
        }.start()
    }

    private fun pulse(target: View) {
        target.animate().scaleX(1.18f).scaleY(1.18f).setDuration(140).withEndAction {
            target.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
        }.start()
    }

    private fun centreIn(root: View, view: View): Pair<Float, Float> {
        val r = IntArray(2)
        val v = IntArray(2)
        root.getLocationInWindow(r)
        view.getLocationInWindow(v)
        return (v[0] - r[0] + view.width / 2f) to (v[1] - r[1] + view.height / 2f)
    }
}
