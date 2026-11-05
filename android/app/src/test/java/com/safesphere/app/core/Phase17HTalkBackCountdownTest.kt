package com.safesphere.app.core

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real-device sprint, Track C (Galaxy A23, TalkBack 14.5): the SOS countdown was silent for a person using TalkBack and
 * CANCEL could not be found within 5 seconds. The fix moves accessibility focus to CANCEL; these scans keep it there.
 */
class Phase17HTalkBackCountdownTest {

    private val strings = File("src/main/res/values/strings.xml").readText(Charsets.UTF_8)
    private val layout = File("src/main/res/layout/fragment_emergency.xml").readText(Charsets.UTF_8)
    private val fragment = File(
        "src/main/java/com/safesphere/app/feature/emergency/ui/EmergencyFragment.kt"
    ).readText(Charsets.UTF_8)

    private fun string(name: String): String =
        Regex("<string name=\"$name\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL).find(strings)?.groupValues?.get(1)
            ?: error("string $name not found")

    @Test
    fun `the spoken label of CANCEL starts with Cancel and says how to stop it, with no number that could go stale`() {
        val label = string("cd_countdown_cancel")
        assertTrue(label.startsWith("Cancel"))
        assertTrue(label.lowercase().contains("double-tap"))
        assertFalse("a fixed number would be wrong when read again later", label.any { it.isDigit() })
    }

    @Test
    fun `the countdown moves accessibility focus to CANCEL once per countdown`() {
        assertTrue(fragment.contains("ACTION_ACCESSIBILITY_FOCUS"))
        assertTrue(fragment.contains("R.string.cd_countdown_cancel"))
        assertTrue("focus is requested once, not on every tick", fragment.contains("if (!cancelFocused)"))
        assertTrue(fragment.contains("cancelFocused = false"))
    }

    @Test
    fun `no per-second announcement and no live region on the number (they cut the speech on the A23)`() {
        assertFalse(fragment.contains("announceForAccessibility(seconds"))
        val number = layout.substringAfter("android:id=\"@+id/countdownNumber\"").substringBefore("/>")
        assertFalse(number.contains("accessibilityLiveRegion"))
    }
}
