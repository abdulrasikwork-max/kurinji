package com.safesphere.app.feature.safewalk

import com.safesphere.app.core.device.BackgroundLimit
import com.safesphere.app.core.device.BackgroundLimits
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real-device sprint, Track E (Galaxy A23, One UI, Android 14). Measured there:
 *  - battery "Optimized" (the default): a Safe Walk (location-only foreground service) was killed by the phone's own process manager
 *    45 s after IRL was swiped away from Recents, and nothing told the person; an SOS survived the same swipe;
 *  - "Restricted": the walk and the SOS lost their location as soon as IRL left the screen;
 *  - "Unrestricted": both kept running.
 * The app cannot change this, so it now says it, once, only when the phone really limits it. N6: "On my way" has no service any
 * more (an alarm reminds), so only the SOS screen speaks.
 */
class Phase17HBackgroundLimitTest {

    private val strings = File("src/main/res/values/strings.xml").readText(Charsets.UTF_8)
    private val emergencyFragment = File(
        "src/main/java/com/safesphere/app/feature/emergency/ui/EmergencyFragment.kt"
    ).readText(Charsets.UTF_8)
    private val manifest = File("src/main/AndroidManifest.xml").readText(Charsets.UTF_8)

    private fun string(name: String): String =
        Regex("<string name=\"$name\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL).find(strings)?.groupValues?.get(1)
            ?: error("string $name not found")

    @Test
    fun `the limit is read from the two platform flags, restriction first`() {
        assertEquals(BackgroundLimit.RESTRICTED, BackgroundLimits.of(restricted = true, ignoringOptimizations = false))
        assertEquals(BackgroundLimit.RESTRICTED, BackgroundLimits.of(restricted = true, ignoringOptimizations = true))
        assertEquals(BackgroundLimit.NONE, BackgroundLimits.of(restricted = false, ignoringOptimizations = true))
        assertEquals(BackgroundLimit.OPTIMIZED, BackgroundLimits.of(restricted = false, ignoringOptimizations = false))
    }

    @Test
    fun `the words are plain, name the fix and promise nothing`() {
        listOf("sos_hint_battery_restricted").forEach { name ->
            val text = string(name)
            assertTrue("$name names the fix", text.contains("Unrestricted") && text.contains("App info"))
            assertTrue("$name offers to keep IRL open", text.contains("keep IRL open") || text.contains("Leave IRL open"))
            assertFalse("$name has no number or placeholder", text.contains("%"))
            val lower = text.lowercase()
            listOf("safe from", "you are safe", "protected", "guarantee", "always").forEach {
                assertFalse("$name must not say \"$it\"", lower.contains(it))
            }
        }
    }

    @Test
    fun `the SOS screen speaks only for Restricted, because an SOS survived Optimized on the A23`() {
        assertTrue(emergencyFragment.contains("== BackgroundLimit.RESTRICTED"))
        assertFalse(emergencyFragment.contains("BackgroundLimit.OPTIMIZED"))
        assertTrue("only while location is being saved", emergencyFragment.contains("ui.sharing.isTracking && BackgroundLimits.read"))
    }

    @Test
    fun `reading the limit needs no new permission and never asks the person to switch battery optimisation off`() {
        assertFalse(manifest.contains("REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"))
        val source = File("src/main/java/com/safesphere/app/core/device/BackgroundLimit.kt").readText(Charsets.UTF_8)
        assertFalse(source.contains("ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"))
        assertTrue("a failed read says nothing", source.contains("getOrDefault(BackgroundLimit.NONE)"))
    }
}
