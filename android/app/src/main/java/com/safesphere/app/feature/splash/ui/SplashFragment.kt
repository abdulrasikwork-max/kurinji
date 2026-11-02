package com.safesphere.app.feature.splash.ui

import android.graphics.drawable.Animatable
import android.os.Bundle
import android.util.TypedValue
import android.view.View
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
 * The IRL opening screen (2026-11-03, docs/DESIGN_SYSTEM.md "Opening"). It continues the system launch screen: "the
 * Meet" rises as the hero, then "OUT THERE. TOGETHER." and the three lines land one after another. On the first run
 * "LET'S GO →" appears and leads to the onboarding; a returning person is taken on by themselves after the short
 * launch time (as before), so the opening never adds a tap on the way to SOS. With the system's animations switched
 * off everything is simply shown. With large text on a narrow screen the decorative hero hides and the headline gets
 * smaller, so the words and the button fit.
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
            Motion.revealInSequence(listOf(binding.openingBrand, binding.openingMotto, binding.openingLineWorld,
                binding.openingLinePeople, binding.openingLineFreedom, binding.openingSupporting))
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

    /** First run: the button fades in once the words have landed. */
    private fun showStart() {
        val start = binding.openingStart
        if (start.isVisible) return
        Motion.revealInSequence(listOf(start))
        start.isVisible = true
    }

    private fun fitToTextSize() {
        val config = resources.configuration
        // The hero never takes more than 30 % of the screen, so on short phones the words still fit above the button.
        val density = resources.displayMetrics.density
        binding.openingArt.maxHeight = (minOf(HERO_MAX_DP, config.screenHeightDp * HERO_SHARE) * density).toInt()
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
        const val HERO_MAX_DP = 300f
        const val HERO_SHARE = 0.30f
    }
}
