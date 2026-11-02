package com.safesphere.app.core

import java.io.File
import kotlin.math.pow
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 13F (docs/PHASE13F_PLAN.md §2, F20): every text colour on the surface it is used on reaches 4.5:1 (WCAG AA),
 * and outlines of controls 3:1, in the day palette (values) and in Kurinji night (values-night, falling back to day).
 */
class ContrastTest {

    @Test
    fun `text pairs reach 4_5 to 1 and control outlines 3 to 1, day and night`() {
        val day = colours(File("src/main/res/values/colors.xml"))
        val night = day + colours(File("src/main/res/values-night/colors.xml"))
        val problems = mutableListOf<String>()
        for ((name, palette) in listOf("day" to day, "night" to night)) {
            for ((fg, bg) in TEXT_PAIRS) check(palette, fg, bg, 4.5, name, problems)
            for ((fg, bg) in OUTLINE_PAIRS) check(palette, fg, bg, 3.0, name, problems)
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    /**
     * Phase 16 (emulator finding): the calm status ("Nothing running") must not be red, or a calm fact reads as an
     * emergency now that red is also the brand.
     */
    @Test
    fun `the calm status accent is not a red`() {
        val day = colours(File("src/main/res/values/colors.xml"))
        val night = day + colours(File("src/main/res/values-night/colors.xml"))
        for (palette in listOf(day, night)) {
            val rgb = palette.getValue("hero_accent")
            val r = rgb shr 16 and 0xFF
            val g = rgb shr 8 and 0xFF
            val b = rgb and 0xFF
            assertTrue("hero_accent looks red: #%06X".format(rgb), !(r > g + 40 && r > b + 40))
        }
    }

    /** Phase 17E: the brand never uses red, so red keeps meaning emergency; the emergency values are unchanged. */
    @Test
    fun `the brand is not red and the emergency colours are unchanged`() {
        val day = colours(File("src/main/res/values/colors.xml"))
        val night = day + colours(File("src/main/res/values-night/colors.xml"))
        for (palette in listOf(day, night)) {
            for (name in listOf("k_action", "k_lime", "k_leaf", "k_panel")) {
                val rgb = palette.getValue(name)
                val r = rgb shr 16 and 0xFF
                val g = rgb shr 8 and 0xFF
                val b = rgb and 0xFF
                assertTrue("$name looks red: #%06X".format(rgb), !(r > g + 40 && r > b + 40))
            }
        }
        assertTrue(day.getValue("ss_emergency") == 0xC8102E && day.getValue("emergency_surface") == 0x0B0B0B)
        assertTrue(night.getValue("ss_emergency") == 0xD42A3D && night.getValue("emergency_surface") == 0x000000)
        assertTrue(day.getValue("launcher_red") == 0xD0102A) // the SOS shortcut's ring
    }

    private fun check(palette: Map<String, Int>, fg: String, bg: String, minimum: Double, theme: String,
                      problems: MutableList<String>) {
        val f = palette[fg] ?: return problems.let { it += "$theme: $fg missing" }
        val b = palette[bg] ?: return problems.let { it += "$theme: $bg missing" }
        val ratio = ratio(f, b)
        if (ratio < minimum) problems += "$theme: $fg on $bg = ${"%.2f".format(ratio)} (< $minimum)"
    }

    private fun colours(file: File): Map<String, Int> =
        Regex("<color name=\"([a-z0-9_]+)\">#([0-9A-Fa-f]{6})</color>").findAll(file.readText(Charsets.UTF_8))
            .associate { it.groupValues[1] to it.groupValues[2].toInt(16) }

    private fun luminance(rgb: Int): Double {
        fun channel(c: Int): Double {
            val s = c / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(rgb shr 16 and 0xFF) + 0.7152 * channel(rgb shr 8 and 0xFF) + 0.0722 * channel(rgb and 0xFF)
    }

    private fun ratio(a: Int, b: Int): Double {
        val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    private companion object {
        val TEXT_PAIRS = listOf(
            "k_ink" to "k_page", "k_ink" to "k_surface", "k_ink" to "k_surface_sunk",
            "k_ink_soft" to "k_page", "k_ink_soft" to "k_surface", "k_ink_soft" to "k_surface_sunk",
            // Phase 17E ("Ink & Lime"): actions (ink + lime by day, lime + ink at night), lime fills, the readable accent.
            "k_on_action" to "k_action", "k_on_lime" to "k_lime", "k_on_lime_soft" to "k_lime_soft",
            "k_leaf" to "k_page", "k_leaf" to "k_surface", "k_leaf" to "k_surface_sunk",
            "k_on_panel" to "k_panel", "k_on_panel_soft" to "k_panel", "k_lime" to "k_panel",
            "ss_caution" to "k_page", "ss_caution" to "k_surface", "ss_on_caution_container" to "ss_caution_container",
            "ss_safe" to "k_page", "ss_on_safe_container" to "ss_safe_container",
            "ss_on_emergency" to "ss_emergency", "ss_on_emergency_container" to "ss_emergency_container",
            "hero_on" to "hero_background", "hero_on_variant" to "hero_background", "hero_accent" to "hero_background",
            "hero_caution" to "hero_background",
            // Phase 16 (U4): emergency words on the black emergency surface; white on every avatar; the splash line.
            "ss_on_emergency" to "emergency_surface",
            "avatar_on" to "avatar_1", "avatar_on" to "avatar_2", "avatar_on" to "avatar_3", "avatar_on" to "avatar_4",
            "avatar_on" to "avatar_5", "avatar_on" to "avatar_6",
            "launcher_chevron" to "splash_background"
        )
        // Control edges: outlines on the page and cards; an action (ink by day) must stand out from the page; a chosen
        // lime pill gets an ink edge (lime alone is 1.2:1 on white).
        val OUTLINE_PAIRS = listOf("k_outline" to "k_page", "k_outline" to "k_surface", "k_action" to "k_page",
            "k_action" to "k_surface", "k_on_lime" to "k_lime",
            // Phase 16: the red ring must stand out from the black emergency surface; the icon's stem from its background.
            "ss_emergency" to "emergency_surface", "launcher_stem" to "launcher_background")
    }
}
