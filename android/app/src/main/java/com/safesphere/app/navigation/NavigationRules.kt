package com.safesphere.app.navigation

import androidx.navigation.NavController
import androidx.navigation.navOptions
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.safesphere.app.R

/** Which screens show which parts of the shared app chrome (toolbar, bottom navigation). */
object NavigationRules {

    /** The five tabs (Rise since Phase 10B). Bottom navigation is visible only on these. */
    val bottomNavDestinations = setOf(
        R.id.homeFragment,
        R.id.safetyFragment,
        R.id.communityFragment,
        R.id.riseFragment,
        R.id.profileFragment
    )

    /** Screens that draw their own header (tabs, login) or are full-screen flows (splash, onboarding). */
    val destinationsWithoutToolbar = setOf(
        R.id.splashFragment,
        R.id.onboardingFragment,
        R.id.loginFragment,
        R.id.homeFragment,
        R.id.safetyFragment,
        R.id.communityFragment,
        R.id.riseFragment,
        R.id.profileFragment
    )

    /** Screens that sit on the dark navy brand colour, so system-bar icons must be light. */
    val destinationsOnHero = setOf(R.id.splashFragment)
}

/**
 * Connects the bottom bar to the NavController.
 *
 * We do this ourselves instead of using NavigationUI because the graph's start destination is the
 * splash screen, which is removed from the back stack after startup. Here every tab switch pops
 * back to Home, and each tab keeps its own state.
 */
fun BottomNavigationView.setUpWithTabs(navController: NavController) {
    setOnItemSelectedListener { item ->
        val options = navOptions {
            launchSingleTop = true
            restoreState = true
            popUpTo(R.id.homeFragment) { saveState = true }
        }
        navController.navigate(item.itemId, null, options)
        true
    }
    // Tapping the tab you are already on does nothing.
    setOnItemReselectedListener { }

    navController.addOnDestinationChangedListener { _, destination, _ ->
        menu.findItem(destination.id)?.isChecked = true
    }
}
