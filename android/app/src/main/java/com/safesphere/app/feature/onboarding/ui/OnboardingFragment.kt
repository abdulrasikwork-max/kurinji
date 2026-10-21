package com.safesphere.app.feature.onboarding.ui

import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isInvisible
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayoutMediator
import com.safesphere.app.R
import com.safesphere.app.appContainer
import com.safesphere.app.core.ui.ViewBindingFragment
import com.safesphere.app.core.ui.collectWhenStarted
import com.safesphere.app.databinding.FragmentOnboardingBinding

class OnboardingFragment :
    ViewBindingFragment<FragmentOnboardingBinding>(FragmentOnboardingBinding::inflate) {

    private val viewModel by viewModels<OnboardingViewModel> {
        OnboardingViewModel.factory(appContainer)
    }

    private val pages = OnboardingPages.all
    private val lastPageIndex = pages.lastIndex

    // System Back moves to the previous page; on the first page it falls through to the system.
    private val previousPageOnBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            binding.onboardingPager.currentItem -= 1
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val pager = binding.onboardingPager

        pager.adapter = OnboardingPagerAdapter(pages)
        TabLayoutMediator(binding.pageIndicator, pager) { tab, position ->
            tab.contentDescription = getString(R.string.cd_onboarding_page, position + 1, pages.size)
        }.attach()

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = updateControls(position)
        })
        updateControls(pager.currentItem)

        binding.buttonNext.setOnClickListener {
            if (pager.currentItem == lastPageIndex) viewModel.completeOnboarding() else pager.currentItem += 1
        }
        binding.buttonBack.setOnClickListener { pager.currentItem -= 1 }
        binding.buttonSkip.setOnClickListener { viewModel.completeOnboarding() }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, previousPageOnBack)

        collectWhenStarted(viewModel.isFinished) { finished ->
            if (finished) openLogin()
        }
    }

    private fun updateControls(position: Int) {
        val isFirst = position == 0
        val isLast = position == lastPageIndex
        binding.buttonBack.isInvisible = isFirst
        binding.buttonSkip.isInvisible = isLast
        binding.buttonNext.setText(if (isLast) R.string.action_get_started else R.string.action_next)
        previousPageOnBack.isEnabled = !isFirst
    }

    private fun openLogin() {
        val navController = findNavController()
        if (navController.currentDestination?.id == R.id.onboardingFragment) {
            navController.navigate(R.id.action_onboarding_to_login)
        }
    }
}
