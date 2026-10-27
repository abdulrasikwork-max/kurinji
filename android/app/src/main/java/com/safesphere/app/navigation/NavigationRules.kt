package com.safesphere.app.navigation

import android.content.res.ColorStateList
import android.graphics.BlendMode
import android.graphics.ColorFilter
import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.navigation.NavController
import androidx.navigation.navOptions
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.safesphere.app.R

/**
 * Which screens show which parts of the shared app chrome (toolbar, bottom bar, safety banners). Phase 13F:
 * Discover · Share · Cocoon · You (docs/PHASE13F_PLAN.md F5, F6).
 */
object NavigationRules {

    /** The three tabs. Share sits between them in the bar but is an action ([SHARE_ITEM]), never a tab. */
    val tabDestinations = setOf(
        R.id.discoverFragment,
        R.id.cocoonFragment,
        R.id.youFragment
    )

    /** The bar item that opens the composer. */
    val SHARE_ITEM = R.id.navShare

    /** Where the app opens once signed in, and where every tab switch pops back to. */
    val START = R.id.discoverFragment

    /**
     * Reading screens keep the bottom bar, so "Want to go" can fly into the Cocoon tab and the person is one tap from
     * any tab. Task screens (composer, place picker, safety flows, settings) keep only the toolbar and back arrow.
     */
    val readingDestinations = setOf(
        R.id.postFragment,
        R.id.postCollectionFragment,
        R.id.personProfileFragment
    )

    val bottomBarDestinations = tabDestinations + readingDestinations

    /** Screens that draw their own header (tabs, login) or are full-screen flows (splash, onboarding). */
    val destinationsWithoutToolbar = setOf(
        R.id.splashFragment,
        R.id.onboardingFragment,
        R.id.loginFragment
    ) + tabDestinations

    /**
     * The safety banners (a running SOS or walk, a pending deletion, "sign in to sync") show on every tab and reading
     * screen, so they can never be missed the way a single Home screen could be. Never on the SOS screen itself
     * (the red screen is the one emergency element there), never before sign-in.
     */
    val bannerDestinations = bottomBarDestinations

    /** Screens shown before (or without) a signed-in session. Everything else is a signed-in screen. */
    val signedOutDestinations = setOf(
        R.id.splashFragment,
        R.id.onboardingFragment,
        R.id.loginFragment,
        R.id.registerFragment,
        R.id.forgotPasswordFragment,
        R.id.phoneVerifyFragment // Phase 17B: no session until the code is right
    )

    /**
     * Phase 13F (F11): the SOS shortcut and tile open the SOS screen from ANY signed-in screen (found on the emulator:
     * from the Safe Walk screen nothing happened). Not before sign-in, and not when the SOS screen is already open.
     */
    fun opensSosFrom(destinationId: Int?): Boolean =
        destinationId != null && destinationId !in signedOutDestinations && destinationId != R.id.emergencyFragment

    /** Screens drawn on the hero colour, so the system-bar icons must be light. None since 13F (the splash is mist). */
    val destinationsOnHero = emptySet<Int>()
}

/**
 * Connects the bottom bar to the NavController.
 *
 * We do this ourselves instead of using NavigationUI because the graph's start destination is the splash screen,
 * which is removed from the back stack after startup. Every tab switch pops back to Discover, and each tab keeps its
 * own state. The Share item opens the composer and leaves the selection where it was.
 */
fun BottomNavigationView.setUpWithTabs(navController: NavController) {
    // The Share disc keeps its own colours (it is an action, not a tab), so the bar's tint is kept off it.
    menu.findItem(NavigationRules.SHARE_ITEM)?.let { item -> item.icon = item.icon?.let(::Untinted) }

    setOnItemSelectedListener { item ->
        if (item.itemId == NavigationRules.SHARE_ITEM) {
            navController.navigate(R.id.composeFragment)
            return@setOnItemSelectedListener false
        }
        val options = navOptions {
            launchSingleTop = true
            restoreState = true
            popUpTo(NavigationRules.START) { saveState = true }
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

/** A drawable that ignores tints, so one bar icon can keep its own colours. */
private class Untinted(drawable: Drawable) : DrawableWrapper(drawable.mutate()) {
    override fun setTintList(tint: ColorStateList?) = Unit
    override fun setTintMode(tintMode: PorterDuff.Mode?) = Unit
    @RequiresApi(Build.VERSION_CODES.Q)
    override fun setTintBlendMode(blendMode: BlendMode?) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
}
