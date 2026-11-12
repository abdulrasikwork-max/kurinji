package com.safesphere.app.core.ui

import android.content.Context
import androidx.appcompat.view.ContextThemeWrapper
import com.safesphere.app.R

/**
 * IRL (2026-11-12): which screens wear the social palette (Black Cherry and Cream Vanilla, ThemeOverlay.IRL.Social).
 *
 * The rule is by package, in ONE place: the social side of IRL (posts, people, places, the You tab, settings, the
 * account page) and the screens before sign-in. Every other screen - Safety, SOS, Safe Walk, the Safety Circle, the
 * vault, Nearby, the area picture, moderation - keeps the base theme exactly as it was, so the emergency and safety
 * colours stay what people have learned. The overlay names no emergency, safe, caution or hero role.
 */
object SocialPalette {

    private const val FEATURES = "com.safesphere.app.feature."

    /** The feature packages on the social side. Anything not listed keeps the base (safety) colours. */
    val socialFeatures = setOf("social", "profile", "settings", "account", "auth", "onboarding")

    /** True for a fragment class of the social side. Null or an unknown class keeps the base colours. */
    fun appliesTo(className: String?): Boolean {
        if (className == null || !className.startsWith(FEATURES)) return false
        return className.removePrefix(FEATURES).substringBefore('.') in socialFeatures
    }

    /** The same context wearing the social palette. */
    fun wrap(base: Context): Context = ContextThemeWrapper(base, R.style.ThemeOverlay_IRL_Social)
}
