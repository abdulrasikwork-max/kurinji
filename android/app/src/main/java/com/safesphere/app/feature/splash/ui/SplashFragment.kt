package com.safesphere.app.feature.splash.ui

import android.animation.ValueAnimator
import android.graphics.drawable.Animatable
import android.os.Bundle
import android.transition.ChangeBounds
import android.transition.TransitionManager
import android.util.TypedValue
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.view.ViewCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.safesphere.app.R
import com.safesphere.app.appContainer
import com.safesphere.app.core.ui.Motion
import com.safesphere.app.core.ui.ViewBindingFragment
import com.safesphere.app.core.ui.collectWhenStarted
import com.safesphere.app.databinding.FragmentSplashBinding
import com.safesphere.app.feature.social.ui.MarksFit
import java.util.Locale

/**
 * The IRL opening screen (2026-11-12, third version; docs/DESIGN_SYSTEM.md "Opening"). It continues the plain cherry
 * launch screen with ONE centred lockup, every time: the mark writes itself (560 ms), "IRL" arrives under it, then
 * "OUT THERE. TOGETHER.". A returning person is taken on after that by themselves (about one second in all), so the
 * opening never adds a tap on the way to SOS. On the first run the lockup then moves up and "YOUR WORLD. / YOUR PEOPLE. /
 * YOUR FREEDOM." lands line by line with "LET'S GO", which leads to the onboarding. With the system's animations
 * switched off everything is simply shown. With large text on a narrow screen the decorative mark hides and the
 * headline gets smaller, so the words and the button fit.
 */
class SplashFragment : ViewBindingFragment<FragmentSplashBinding>(FragmentSplashBinding::inflate) {

    private val viewModel by viewModels<SplashViewModel> { SplashViewModel.factory(appContainer) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        fitToTextSize()
        binding.openingHeadline.doOnLayout { fitHeadlineToWidth() }
        ViewCompat.setAccessibilityHeading(binding.openingHeadline, true)
        (binding.openingArt.drawable as? Animatable)?.start()
        if (savedInstanceState == null) {
            // The name arrives while the mark is still being written; the line follows it.
            Motion.revealInSequence(
                listOf(binding.openingWordmark, binding.openingMotto),
                startDelayMs = NAME_AFTER_MS,
                staggerMs = LINE_AFTER_NAME_MS
            )
        }
        binding.openingStart.setOnClickListener { navigateTo(StartDestination.Onboarding) }
        collectWhenStarted(viewModel.destination) { destination ->
            when {
                destination == null -> Unit
                SplashViewModel.waitsForStart(destination) -> showStart()
                else -> navigateTo(destination)
            }
        }
    }

    /**
     * First run only: once the lockup is complete it glides up to make room, the three lines land one after another and
     * the button with them. A returning person never sees this: they are taken on after the lockup.
     */
    private fun showStart() {
        val start = binding.openingStart
        if (start.isVisible) return
        val animated = ValueAnimator.areAnimatorsEnabled()
        if (animated) {
            TransitionManager.beginDelayedTransition(
                binding.openingContent,
                ChangeBounds().setDuration(MAKE_ROOM_MS).setInterpolator(DecelerateInterpolator())
            )
        }
        binding.openingHeadline.isVisible = true
        start.isVisible = true
        Motion.revealInSequence(headlineLines + start, startDelayMs = LINES_AFTER_MS, staggerMs = BETWEEN_LINES_MS)
    }

    private fun fitToTextSize() {
        val config = resources.configuration
        // The mark never takes more than a fifth of the screen height, so on short phones the words still fit.
        val markPx = minOf(
            resources.getDimension(R.dimen.opening_mark),
            config.screenHeightDp * MARK_SHARE * resources.displayMetrics.density
        ).toInt()
        binding.openingArt.layoutParams = binding.openingArt.layoutParams.apply {
            width = markPx
            height = markPx
        }
        if (MarksFit.mode(config.screenWidthDp, config.fontScale) != MarksFit.Mode.ICONS) return
        binding.openingArt.isVisible = false
        setHeadlineSize(resources.getDimension(R.dimen.opening_headline_tight))
    }

    private val headlineLines
        get() = listOf(binding.openingLineWorld, binding.openingLinePeople, binding.openingLineFreedom)

    /** One size for the three lines; the overlap that pulls the lines together scales with it (-16dp at 46sp). */
    private fun setHeadlineSize(sizePx: Float) {
        val baseSize = resources.getDimension(R.dimen.opening_headline)
        val overlap = resources.getDimension(R.dimen.opening_headline_overlap) * (sizePx / baseSize)
        headlineLines.forEachIndexed { index, line ->
            line.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx)
            if (index > 0) {
                line.layoutParams = (line.layoutParams as android.view.ViewGroup.MarginLayoutParams)
                    .apply { topMargin = overlap.toInt() }
            }
        }
    }

    /**
     * Each headline line stays on one line (a wrapped "YOUR / FREEDOM." collides in this tight display face): when the
     * widest line does not fit, all three shrink to one shared size that does.
     */
    private fun fitHeadlineToWidth() {
        val lines = headlineLines
        val available = binding.openingHeadline.width.toFloat()
        if (available <= 0f) return
        val widest = lines.maxOf { line ->
            line.paint.measureText(line.text.toString().uppercase(Locale.getDefault()))
        }
        if (widest <= available) return
        setHeadlineSize(lines.first().textSize * (available / widest) * FIT_MARGIN)
    }

    private fun navigateTo(destination: StartDestination) {
        val navController = findNavController()
        // Guard against navigating twice if the view is recreated right after the decision.
        if (navController.currentDestination?.id != R.id.splashFragment) return

        val actionId = when (destination) {
            StartDestination.Onboarding -> R.id.action_splash_to_onboarding
            StartDestination.Login -> R.id.action_splash_to_login
            StartDestination.Home -> R.id.action_splash_to_start
        }
        navController.navigate(actionId)
    }

    private companion object {
        /** A little room so the widest line never touches the edge. */
        const val FIT_MARGIN = 0.97f
        const val MARK_SHARE = 0.20f

        /** "IRL" starts to arrive while the mark is still being written (560 ms); the line follows. */
        const val NAME_AFTER_MS = 300L
        const val LINE_AFTER_NAME_MS = 220L

        /** First run: the lockup makes room, then the three lines and the button land. */
        const val MAKE_ROOM_MS = 380L
        const val LINES_AFTER_MS = 220L
        const val BETWEEN_LINES_MS = 120L
    }
}
