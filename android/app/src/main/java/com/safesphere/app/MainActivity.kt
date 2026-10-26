package com.safesphere.app

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.color.MaterialColors
import com.safesphere.app.core.ui.showSessionExpiredDialog
import com.safesphere.app.databinding.ActivityMainBinding
import com.safesphere.app.feature.account.ui.AccountTexts
import com.safesphere.app.feature.safety.ui.ActiveSessionBanner
import com.safesphere.app.feature.safety.ui.SessionStatusViewModel
import com.safesphere.app.navigation.NavigationRules
import com.safesphere.app.navigation.setUpWithTabs
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The only Activity. It owns the shared chrome (toolbar, safety banners, bottom bar) and hosts every screen as a
 * Fragment through the Navigation Component. Phase 13F: the safety banners that lived on Home (an SOS or Safe Walk
 * running, a pending deletion, "sign in to sync") are shown here, on every tab and reading screen.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController

    /** Created on the first screen that shows banners (after sign-in), then kept for the activity's life. */
    private val session by viewModels<SessionStatusViewModel> { SessionStatusViewModel.factory(appContainer) }
    private var bannersStarted = false
    private var bannersAllowed = false
    /** Phase 15: the last banner state had something to show (SOS, walk, deletion or "sign in to sync"). */
    private var anyBannerDue = false

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
        val appBarConfiguration = AppBarConfiguration(NavigationRules.tabDestinations)
        binding.toolbar.setupWithNavController(navController, appBarConfiguration)
        binding.bottomNavigation.setUpWithTabs(navController)

        navController.addOnDestinationChangedListener { _, destination, _ ->
            updateChrome(destination)
        }
    }

    private fun updateChrome(destination: NavDestination) {
        binding.toolbar.isVisible = destination.id !in NavigationRules.destinationsWithoutToolbar
        binding.bottomNavigation.isVisible = destination.id in NavigationRules.bottomBarDestinations
        bannersAllowed = destination.id in NavigationRules.bannerDestinations
        if (bannersAllowed) startBanners()
        // Phase 15: re-applied on every screen change; the banner state does not change when the person comes back
        // to a tab from a screen without banners (e.g. after starting a Safe Walk or asking to delete the account).
        binding.banners.sessionBanners.isVisible = bannersAllowed && anyBannerDue
        if (bannersStarted && bannersAllowed) session.onScreenVisible()
        if (NavigationRules.opensSosFrom(destination.id)) openSosIfRequested()
        updateSystemBars(onHero = destination.id in NavigationRules.destinationsOnHero)
    }

    /** The SOS shortcut or tile while the app was already open (SosEntryActivity brings this activity to the front). */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (NavigationRules.opensSosFrom(navController.currentDestination?.id)) openSosIfRequested()
    }

    /**
     * Phase 13F (F11, F3): only a request recorded in this process by the non-exported SosEntryActivity (the shortcut
     * or the tile) opens the SOS screen with its cancellable countdown already running; nothing in an Intent can.
     * Reached only on signed-in screens; a request older than 15 s (for example after signing in first) is dropped.
     */
    private fun openSosIfRequested() {
        if (!appContainer.sosLaunchRequest.consume(android.os.SystemClock.elapsedRealtime())) return
        navController.navigate(R.id.emergencyFragment,
            androidx.core.os.bundleOf(com.safesphere.app.feature.emergency.ui.EmergencyFragment.ARG_AUTO_START to true))
    }

    /** Phase 13F (F6): the banners of the old Home screen, with the same words, colours and destinations. */
    private fun startBanners() {
        if (bannersStarted) return
        bannersStarted = true
        val banners = binding.banners
        banners.activeEmergencyBanner.setOnClickListener { navController.navigate(R.id.emergencyFragment) }
        banners.activeWalkBanner.setOnClickListener { navController.navigate(R.id.safeWalkFragment) }
        // Phase 10A.3: a pending account deletion is visible for the whole grace period.
        banners.deletionBanner.setOnClickListener { navController.navigate(R.id.accountFragment) }
        // Phase 11 C1: "sign in to sync" (calm, not a forced dialog): SOS keeps working, only server sync waits.
        banners.signInBanner.setOnClickListener { navController.navigate(R.id.action_global_to_login) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(session.activeSession, session.deletion, session.signInNeeded) { active, deletion, signIn ->
                    Triple(active, deletion, signIn)
                }.collect { (active, deletion, signIn) ->
                    banners.activeEmergencyBanner.isVisible = active is ActiveSessionBanner.Emergency
                    banners.activeWalkBanner.isVisible = active is ActiveSessionBanner.SafeWalk
                    if (active is ActiveSessionBanner.SafeWalk) {
                        banners.activeWalkBanner.setWarning(active.overdue)
                        val text = getString(if (active.overdue) R.string.home_walk_banner_overdue else R.string.home_walk_banner)
                        banners.activeWalkBanner.setMessage(text)
                        banners.activeWalkBanner.contentDescription = text
                    }
                    banners.deletionBanner.isVisible = deletion != null
                    if (deletion != null) {
                        val text = AccountTexts.deletionBanner(this@MainActivity, deletion)
                        banners.deletionBanner.setMessage(text)
                        banners.deletionBanner.contentDescription = getString(R.string.cd_home_deletion_banner, text)
                    }
                    banners.signInBanner.isVisible = signIn
                    anyBannerDue = active != null || deletion != null || signIn
                    banners.sessionBanners.isVisible = bannersAllowed && anyBannerDue
                }
            }
        }
        // Phase 13F (D9): Rise was retired and its notes on this phone deleted; say so once.
        lifecycleScope.launch {
            val retirement = appContainer.riseRetirement
            val pending = withContext(Dispatchers.IO) { retirement.retire(); retirement.noticePending() }
            if (pending) {
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(R.string.rise_retired_title)
                    .setMessage(R.string.rise_retired_message)
                    .setPositiveButton(R.string.action_got_it) { _, _ -> retirement.noticeShown() }
                    .setOnCancelListener { retirement.noticeShown() }
                    .show()
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                session.sessionExpired.collect { expired ->
                    val fragment = supportFragmentManager.findFragmentById(R.id.navHostFragment)
                        ?.childFragmentManager?.primaryNavigationFragment
                    if (expired && fragment != null) {
                        session.onSessionExpiredHandled()
                        fragment.showSessionExpiredDialog()
                    }
                }
            }
        }
    }

    /**
     * Every screen sits on the mist (or Kurinji night) surface. Status-bar and navigation-bar icons are switched
     * between light and dark to stay readable.
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
