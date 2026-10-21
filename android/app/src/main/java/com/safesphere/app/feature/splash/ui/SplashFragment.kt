package com.safesphere.app.feature.splash.ui

import android.os.Bundle
import android.view.View
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.safesphere.app.R
import com.safesphere.app.appContainer
import com.safesphere.app.core.ui.Motion
import com.safesphere.app.core.ui.ViewBindingFragment
import com.safesphere.app.core.ui.collectWhenStarted
import com.safesphere.app.databinding.FragmentSplashBinding

class SplashFragment : ViewBindingFragment<FragmentSplashBinding>(FragmentSplashBinding::inflate) {

    private val viewModel by viewModels<SplashViewModel> { SplashViewModel.factory(appContainer) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (savedInstanceState == null) {
            Motion.revealInSequence(listOf(binding.splashMark, binding.splashName, binding.splashTagline))
        }
        collectWhenStarted(viewModel.destination) { destination ->
            if (destination != null) navigateTo(destination)
        }
    }

    private fun navigateTo(destination: StartDestination) {
        val navController = findNavController()
        // Guard against navigating twice if the view is recreated right after the decision.
        if (navController.currentDestination?.id != R.id.splashFragment) return

        val actionId = when (destination) {
            StartDestination.Onboarding -> R.id.action_splash_to_onboarding
            StartDestination.Login -> R.id.action_splash_to_login
            StartDestination.Home -> R.id.action_splash_to_home
        }
        navController.navigate(actionId)
    }
}
