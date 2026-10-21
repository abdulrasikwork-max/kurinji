package com.safesphere.app.feature.home.ui

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.navigation.navOptions
import com.safesphere.app.R
import com.safesphere.app.appContainer
import com.safesphere.app.core.ui.Motion
import com.safesphere.app.core.ui.ViewBindingFragment
import com.safesphere.app.core.ui.collectWhenStarted
import com.safesphere.app.core.ui.showSessionExpiredDialog
import com.safesphere.app.databinding.FragmentHomeBinding
import com.safesphere.app.feature.account.ui.AccountTexts
import java.time.LocalTime

/**
 * Safety Command Center. SOS, Safe Walk and Safety Circle open real screens, and a banner leads back
 * to a running emergency or walk (Phase 4B), or to a pending account deletion (Phase 10A). The status card states only what this phone knows (an SOS, a Safe
 * Walk, or neither); it never says a place or a person is safe.
 */
class HomeFragment : ViewBindingFragment<FragmentHomeBinding>(FragmentHomeBinding::inflate) {

    private val viewModel by viewModels<HomeViewModel> { HomeViewModel.factory(appContainer) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // SOS opens the SOS screen (cancellable countdown); the Safety Circle is texted only with automatic SMS on.
        binding.sosTile.setOnClickListener { findNavController().navigate(R.id.emergencyFragment) }
        binding.quickActions.adapter = QuickActionAdapter(QuickActions.all) { action ->
            findNavController().navigate(action.destinationId)
        }
        binding.profileButton.setOnClickListener { findNavController().navigate(R.id.profileFragment) }
        // Phase 10A.3: a pending account deletion is visible on Home for the whole grace period.
        binding.deletionBanner.setOnClickListener { findNavController().navigate(R.id.accountFragment) }

        binding.activeEmergencyBanner.setOnClickListener { findNavController().navigate(R.id.emergencyFragment) }
        binding.activeWalkBanner.setOnClickListener { findNavController().navigate(R.id.safeWalkFragment) }
        // Phase 6: same as tapping the Community tab (the bottom bar follows the destination).
        binding.nearbyCard.setOnClickListener {
            findNavController().navigate(R.id.communityFragment, null, navOptions {
                launchSingleTop = true
                restoreState = true
                popUpTo(R.id.homeFragment) { saveState = true }
            })
        }

        collectWhenStarted(viewModel.displayName, ::renderGreeting)
        collectWhenStarted(viewModel.activeSession, ::renderActiveSession)
        collectWhenStarted(viewModel.status, ::renderStatus)
        collectWhenStarted(viewModel.deletion) { notice ->
            binding.deletionBanner.isVisible = notice != null
            if (notice != null) {
                val text = AccountTexts.deletionBanner(requireContext(), notice)
                binding.deletionBanner.setMessage(text)
                binding.deletionBanner.contentDescription = getString(R.string.cd_home_deletion_banner, text)
            }
        }
        collectWhenStarted(viewModel.sessionExpired) { expired ->
            if (expired) {
                viewModel.onSessionExpiredHandled()
                showSessionExpiredDialog()
            }
        }

        // Cards fade in only on a fresh start, not after rotation.
        if (savedInstanceState == null) {
            Motion.revealInSequence(
                listOf(
                    binding.homeHeader, binding.greetingBlock, binding.statusCard,
                    binding.quickActionsSection, binding.nearbySection, binding.footerSection
                )
            )
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.onScreenVisible()
    }

    private fun renderActiveSession(banner: ActiveSessionBanner?) {
        binding.activeEmergencyBanner.isVisible = banner is ActiveSessionBanner.Emergency
        binding.activeWalkBanner.isVisible = banner is ActiveSessionBanner.SafeWalk
        if (banner is ActiveSessionBanner.SafeWalk) {
            val overdue = banner.overdue
            binding.activeWalkBanner.setWarning(overdue)
            val text = getString(if (overdue) R.string.home_walk_banner_overdue else R.string.home_walk_banner)
            binding.activeWalkBanner.setMessage(text)
            binding.activeWalkBanner.contentDescription = text
        }
    }

    private fun renderStatus(status: HomeStatus) {
        val content = HomeStatusCard.contentFor(status)
        binding.statusCard.isVisible = content != null
        if (content != null) binding.statusCard.setContent(content)
    }

    private fun renderGreeting(name: String?) {
        val greeting = getString(
            when (GreetingProvider.periodFor(LocalTime.now().hour)) {
                GreetingPeriod.MORNING -> R.string.greeting_morning
                GreetingPeriod.AFTERNOON -> R.string.greeting_afternoon
                GreetingPeriod.EVENING -> R.string.greeting_evening
            }
        )
        binding.greeting.text =
            if (name.isNullOrBlank()) greeting else getString(R.string.greeting_with_name, greeting, name)
    }
}
