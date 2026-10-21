package com.safesphere.app

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.color.MaterialColors
import com.safesphere.app.databinding.ActivityMainBinding
import com.safesphere.app.navigation.NavigationRules
import com.safesphere.app.navigation.setUpWithTabs

/**
 * The only Activity. It owns the shared "chrome" (toolbar and bottom navigation) and hosts
 * every screen as a Fragment through the Navigation Component.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen() // must be called before super.onCreate
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets()

        val navHost = supportFragmentManager.findFragmentById(R.id.navHostFragment) as NavHostFragment
        navController = navHost.navController

        // Tab screens have no "up" arrow; every other screen with a toolbar does.
        val appBarConfiguration = AppBarConfiguration(NavigationRules.bottomNavDestinations)
        binding.toolbar.setupWithNavController(navController, appBarConfiguration)
        binding.bottomNavigation.setUpWithTabs(navController)

        navController.addOnDestinationChangedListener { _, destination, _ ->
            updateChrome(destination)
        }
    }

    private fun updateChrome(destination: NavDestination) {
        binding.toolbar.isVisible = destination.id !in NavigationRules.destinationsWithoutToolbar
        binding.bottomNavigation.isVisible = destination.id in NavigationRules.bottomNavDestinations
        updateSystemBars(onHero = destination.id in NavigationRules.destinationsOnHero)
    }

    /**
     * The splash is full-bleed navy, every other screen sits on the normal surface colour.
     * Status-bar and navigation-bar icons are switched between light and dark to stay readable.
     */
    private fun updateSystemBars(onHero: Boolean) {
        val isNightMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val needsLightIcons = onHero || isNightMode

        WindowCompat.getInsetsController(window, binding.root).apply {
            isAppearanceLightStatusBars = !needsLightIcons
            isAppearanceLightNavigationBars = !needsLightIcons
        }
        val backgroundAttr = if (onHero) R.attr.colorHero else com.google.android.material.R.attr.colorSurface
        binding.root.setBackgroundColor(MaterialColors.getColor(binding.root, backgroundAttr))
    }

    /**
     * The app draws edge-to-edge (required on recent Android versions), so we keep content clear of
     * the status bar, navigation bar, display cutout and on-screen keyboard.
     */
    private fun applySystemBarInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime()
            )
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }
}
