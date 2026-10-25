package com.safesphere.app.navigation

import com.safesphere.app.R
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 13F (docs/PHASE13F_PLAN.md F5, F6): Discover · Share · Cocoon · You, and where the chrome shows. */
class NavigationRulesTest {

    @Test
    fun `three tabs, Share is an action between them, and the app opens on Discover`() {
        assertEquals(setOf(R.id.discoverFragment, R.id.cocoonFragment, R.id.youFragment), NavigationRules.tabDestinations)
        assertEquals(R.id.discoverFragment, NavigationRules.START)
        assertFalse(NavigationRules.SHARE_ITEM in NavigationRules.tabDestinations)

        val menu = File("src/main/res/menu/bottom_navigation.xml").readText(Charsets.UTF_8)
        val ids = Regex("android:id=\"@\\+id/([A-Za-z]+)\"").findAll(menu).map { it.groupValues[1] }.toList()
        assertEquals(listOf("discoverFragment", "navShare", "cocoonFragment", "youFragment"), ids)

        val graph = File("src/main/res/navigation/nav_graph.xml").readText(Charsets.UTF_8)
        for (action in listOf("action_splash_to_start", "action_login_to_start", "action_register_to_start")) {
            val block = graph.substringAfter("android:id=\"@+id/$action\"").substringBefore("/>")
            assertTrue("$action must open Discover", block.contains("app:destination=\"@id/discoverFragment\""))
        }
    }

    @Test
    fun `reading screens keep the bar, task and safety screens do not`() {
        val reading = setOf(R.id.postFragment, R.id.postCollectionFragment, R.id.personProfileFragment)
        assertTrue(NavigationRules.bottomBarDestinations.containsAll(reading))
        for (task in listOf(R.id.composeFragment, R.id.placePickerFragment, R.id.emergencyFragment, R.id.safeWalkFragment,
                R.id.safetyFragment, R.id.settingsFragment, R.id.accountFragment)) {
            assertFalse("$task", task in NavigationRules.bottomBarDestinations)
        }
    }

    @Test
    fun `the SOS shortcut and tile work from any signed-in screen, never before sign-in or over the SOS screen`() {
        for (screen in listOf(R.id.discoverFragment, R.id.postFragment, R.id.safeWalkFragment, R.id.composeFragment,
                R.id.settingsFragment, R.id.safetyFragment)) assertTrue("$screen", NavigationRules.opensSosFrom(screen))
        for (screen in listOf(R.id.splashFragment, R.id.onboardingFragment, R.id.loginFragment, R.id.registerFragment,
                R.id.emergencyFragment, null)) assertFalse("$screen", NavigationRules.opensSosFrom(screen))
    }

    @Test
    fun `the safety banners show on every tab and reading screen, never on the SOS screen or before sign-in`() {
        assertEquals(NavigationRules.bottomBarDestinations, NavigationRules.bannerDestinations)
        for (never in listOf(R.id.emergencyFragment, R.id.splashFragment, R.id.onboardingFragment, R.id.loginFragment)) {
            assertFalse("$never", never in NavigationRules.bannerDestinations)
        }
    }
}
