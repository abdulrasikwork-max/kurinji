package com.safesphere.app.core

import com.safesphere.app.core.ui.SocialPalette
import java.io.File
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * IRL (2026-11-12, the owner's decision): Black Cherry and Cream Vanilla on the SOCIAL side only. This test keeps the
 * three promises of that decision: the social colours can be read (day and night), the safety colours are exactly what
 * they were, and no safety screen can wear the social palette.
 */
class SocialPaletteTest {

    private val day = colours(File("src/main/res/values/colors.xml"))
    private val night = day + colours(File("src/main/res/values-night/colors.xml"))
    private val themes = File("src/main/res/values/themes.xml").readText(Charsets.UTF_8)
    private val overlay = themes.substringAfter("<style name=\"ThemeOverlay.IRL.Social\" parent=\"\">").substringBefore("</style>")

    @Test
    fun `the two brand colours are the ones the owner chose`() {
        assertEquals(0x5D0703, day.getValue("brand_cherry"))
        assertEquals(0xEEDCC8, day.getValue("brand_cream"))
        assertEquals(0x5D0703, day.getValue("c_action"))
        assertEquals(0xEEDCC8, day.getValue("c_page"))
        assertEquals("cream is the action colour at night", 0xEEDCC8, night.getValue("c_action"))
    }

    @Test
    fun `social text pairs reach 4_5 to 1 and control edges 3 to 1, day and night`() {
        val problems = mutableListOf<String>()
        for ((name, palette) in listOf("day" to day, "night" to night)) {
            for ((fg, bg) in TEXT_PAIRS) check(palette, fg, bg, 4.5, name, problems)
            for ((fg, bg) in EDGE_PAIRS) check(palette, fg, bg, 3.0, name, problems)
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun `black cherry is never text on the dark page`() {
        // It is about 1.5:1 on black: at night it may only be a surface (panel, tonal fill) with cream words on it.
        for (name in listOf("c_ink", "c_ink_soft", "c_action", "c_accent", "c_outline", "c_fill")) {
            assertTrue("$name at night", night.getValue(name) != 0x5D0703)
        }
    }

    @Test
    fun `the safety and emergency colours are exactly what they were`() {
        val expectedDay = mapOf("k_page" to 0xF3F4EE, "k_surface" to 0xFFFFFF, "k_ink" to 0x0E100C, "k_action" to 0x0E100C,
            "k_on_action" to 0xADFF2F, "k_lime" to 0xADFF2F, "k_leaf" to 0x3A6100, "k_panel" to 0x0E100C,
            "ss_emergency" to 0xC8102E, "ss_on_emergency" to 0xFFFFFF, "emergency_surface" to 0x0B0B0B,
            "ss_safe" to 0x187A4B, "ss_caution" to 0x8A5A00, "hero_background" to 0x121212, "hero_accent" to 0xA9C7E8,
            "launcher_red" to 0xD0102A, "launcher_sos" to 0x121212)
        val expectedNight = mapOf("k_page" to 0x0B0C0A, "k_surface" to 0x161814, "k_ink" to 0xF1F4EA, "k_action" to 0xADFF2F,
            "k_on_action" to 0x0E100C, "ss_emergency" to 0xD42A3D, "emergency_surface" to 0x000000,
            "ss_safe" to 0x5BD694, "ss_caution" to 0xE8B54A, "hero_background" to 0x1E1E21)
        expectedDay.forEach { (name, value) -> assertEquals("day $name", value, day.getValue(name)) }
        expectedNight.forEach { (name, value) -> assertEquals("night $name", value, night.getValue(name)) }
    }

    @Test
    fun `the overlay names only social colours and no emergency, status or hero role`() {
        val used = Regex("@color/([a-z_0-9]+)").findAll(overlay).map { it.groupValues[1] }.toSet()
        assertTrue("only c_* colours: $used", used.isNotEmpty() && used.all { it.startsWith("c_") })
        for (role in listOf("colorError", "colorOnError", "colorErrorContainer", "colorEmergencySurface", "colorSafe",
            "colorCaution", "colorHero", "colorOnHero", "colorHeroAccent")) {
            assertFalse("the overlay must not touch $role", overlay.contains("name=\"$role\""))
        }
        // The base theme never points at a social colour: a screen without the overlay cannot show one.
        val base = themes.substringAfter("<style name=\"Base.Theme.SafeSphere\"").substringBefore("</style>")
        assertFalse(base.contains("@color/c_") || base.contains("@color/brand_"))
    }

    @Test
    fun `no safety screen wears the social palette`() {
        val root = File("src/main/java/com/safesphere/app/feature")
        val safetyFeatures = setOf("safety", "emergency", "safewalk", "safetycircle", "circlealert", "evidence", "community",
            "intelligence", "moderation")
        val fragments = root.walkTopDown().filter { it.isFile && it.name.endsWith("Fragment.kt") }.toList()
        assertTrue(fragments.size > 30)
        for (file in fragments) {
            val className = "com.safesphere.app.feature." +
                file.relativeTo(root).path.replace(File.separatorChar, '.').removeSuffix(".kt")
            val feature = className.removePrefix("com.safesphere.app.feature.").substringBefore('.')
            if (feature in safetyFeatures) assertFalse(className, SocialPalette.appliesTo(className))
            if (feature == "social" || feature == "profile") assertTrue(className, SocialPalette.appliesTo(className))
        }
        assertTrue(SocialPalette.socialFeatures.intersect(safetyFeatures).isEmpty())
        assertFalse(SocialPalette.appliesTo(null))
        assertFalse(SocialPalette.appliesTo("com.safesphere.app.feature.emergency.ui.EmergencyFragment"))
        assertFalse(SocialPalette.appliesTo("androidx.navigation.fragment.NavHostFragment"))
        // Safety layouts and code never name a social or brand colour directly.
        val layouts = File("src/main/res/layout").listFiles()!!.filter {
            it.name.contains("emergency") || it.name.contains("safety") || it.name.contains("safe_walk") ||
                it.name.contains("vault") || it.name.contains("circle")
        }
        assertTrue(layouts.isNotEmpty())
        layouts.forEach { layout ->
            val text = layout.readText(Charsets.UTF_8)
            assertFalse(layout.name, text.contains("@color/c_") || text.contains("@color/brand_") ||
                text.contains("ThemeOverlay.IRL.Social"))
        }
    }

    private fun check(palette: Map<String, Int>, fg: String, bg: String, minimum: Double, theme: String,
                      problems: MutableList<String>) {
        val f = palette[fg] ?: return problems.let { it += "$theme: $fg missing" }
        val b = palette[bg] ?: return problems.let { it += "$theme: $bg missing" }
        val ratio = ratio(f, b)
        if (ratio < minimum) problems += "$theme: $fg on $bg = ${"%.2f".format(ratio)} (< $minimum)"
    }

    private fun ratio(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun luminance(rgb: Int): Double {
        fun channel(c: Int): Double {
            val s = c / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(rgb shr 16 and 0xFF) + 0.7152 * channel(rgb shr 8 and 0xFF) + 0.0722 * channel(rgb and 0xFF)
    }

    private fun colours(file: File): Map<String, Int> =
        Regex("<color name=\"([a-z_0-9]+)\">#([0-9A-Fa-f]{6})</color>").findAll(file.readText(Charsets.UTF_8))
            .associate { it.groupValues[1] to it.groupValues[2].toInt(16) }

    private companion object {
        val SURFACES = listOf("c_page", "c_surface", "c_surface_sunk", "c_surface_low", "c_surface_container",
            "c_surface_high", "c_surface_highest")
        val TEXT_PAIRS = SURFACES.flatMap { listOf("c_ink" to it, "c_ink_soft" to it, "c_accent" to it) } + listOf(
            "c_on_action" to "c_action", "c_on_fill" to "c_fill", "c_on_soft" to "c_soft",
            "c_on_panel" to "c_panel", "c_on_panel_soft" to "c_panel",
            // The emergency and status words that can appear on a social page keep their own colours: still readable.
            "ss_on_emergency" to "ss_emergency", "ss_on_emergency_container" to "ss_emergency_container",
            "ss_on_caution_container" to "ss_caution_container", "ss_on_safe_container" to "ss_safe_container")
        val EDGE_PAIRS = listOf("c_outline" to "c_page", "c_outline" to "c_surface", "c_action" to "c_page",
            "c_action" to "c_surface", "c_fill" to "c_page", "c_fill" to "c_surface")
    }
}
