package com.safesphere.app.feature.splash.ui

import android.animation.ValueAnimator
import android.os.Bundle
import android.view.View
import android.view.animation.OvershootInterpolator
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.safesphere.app.R
import com.safesphere.app.appContainer
import com.safesphere.app.core.ui.Motion
import com.safesphere.app.core.ui.ViewBindingFragment
import com.safesphere.app.core.ui.collectWhenStarted
import com.safesphere.app.databinding.FragmentSplashBinding

/**
 * Phase 16: the opening moment on brand red. The system launch screen draws the Go-K; here the wordmark rises and
 * "Go. See. Tell." lands one word at a time (≈1 s, inside the existing minimum display time, so start-up is not
 * slower). With the system's animations switched off everything is simply shown.
 */
class SplashFragment : ViewBindingFragment<FragmentSplashBinding>(FragmentSplashBinding::inflate) {

    private val viewModel by viewModels<SplashViewModel> { SplashViewModel.factory(appContainer) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (savedInstanceState == null) {
            Motion.revealInSequence(listOf(binding.splashMark, binding.splashName, binding.splashNameTamil))
            landWords(listOf(binding.taglineGo, binding.taglineSee, binding.taglineTell))
        }
        collectWhenStarted(viewModel.destination) { destination ->
            if (destination != null) navigateTo(destination)
        }
    }

    /** Each word pops in (small overshoot), 170 ms after the previous one. */
    private fun landWords(words: List<View>) {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        words.forEachIndexed { index, word ->
            word.alpha = 0f
            word.scaleX = WORD_START_SCALE
            word.scaleY = WORD_START_SCALE
            word.animate()
                .alpha(1f).scaleX(1f).scaleY(1f)
                .setStartDelay(WORD_FIRST_DELAY_MS + index * WORD_STAGGER_MS)
                .setDuration(WORD_DURATION_MS)
                .setInterpolator(OvershootInterpolator(2f))
                .start()
        }
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
        const val WORD_START_SCALE = 0.6f
        const val WORD_FIRST_DELAY_MS = 260L
        const val WORD_STAGGER_MS = 170L
        const val WORD_DURATION_MS = 260L
    }
}
