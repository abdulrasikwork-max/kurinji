package com.safesphere.app.navigation

import com.safesphere.app.R
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where the chrome shows. N4 (docs/PHASE_N4.md §10): Today · You (Create joins the bar with its screen; Replies in N5). */
class NavigationRulesTest {

    @Test
    fun `two tabs, and the app opens on Today`() {
        assertEquals(setOf(R.id.todayFragment, R.id.youFragment), NavigationRules.tabDestinations)
        assertEquals(R.id.todayFragment, NavigationRules.START)

        val menu = File("src/main/res/menu/bottom_navigation.xml").readText(Charsets.UTF_8)
        val ids = Regex("android:id=\"@\\+id/([A-Za-z]+)\"").findAll(menu).map { it.groupValues[1] }.toList()
        assertEquals(listOf("todayFragment", "youFragment"), ids)

        val graph = File("src/main/res/navigation/nav_graph.xml").readText(Charsets.UTF_8)
        // Phase N2: only the code screen signs in (then Today, or the profile first; a new number: name and age).
        for (action in listOf("action_splash_to_start", "action_phone_verify_to_start",
                "action_profile_setup_to_start")) {
            val block = graph.substringAfter("android:id=\"@+id/$action\"").substringBefore("/>")
            assertTrue("$action must open Today", block.contains("app:destination=\"@id/todayFragment\""))
        }
    }

    @Test
    fun `reading screens keep the bar, task and safety screens do not`() {
        val reading = setOf(R.id.momentFragment, R.id.personProfileFragment)
        assertTrue(NavigationRules.bottomBarDestinations.containsAll(reading))
        for (task in listOf(R.id.moreFragment, R.id.connectionsFragment, R.id.emergencyFragment, R.id.safeWalkFragment,
                R.id.safetyFragment, R.id.settingsFragment, R.id.accountFragment)) {
            assertFalse("$task", task in NavigationRules.bottomBarDestinations)
        }
    }

    @Test
    fun `the SOS shortcut and tile work from any signed-in screen, never before sign-in or over the SOS screen`() {
        for (screen in listOf(R.id.todayFragment, R.id.momentFragment, R.id.youFragment, R.id.safeWalkFragment,
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
