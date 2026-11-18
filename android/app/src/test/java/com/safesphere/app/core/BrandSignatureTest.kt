package com.safesphere.app.core

import java.io.File
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * N7 A1 (D39-D41 / N-131 to N-133): the IRL signature is ONE drawing everywhere (launcher, themed icon, in-app mark,
 * the opening, the notification icon); nothing of the Tamil letter mark (IRL-C10) or of the shield is left on a surface
 * people see outside the app; emergencies keep the siren; the opening says the approved words.
 */
class BrandSignatureTest {

    private val res = File("src/main/res")
    private val main = File("src/main")
    private fun drawable(name: String) = File(res, "drawable/$name.xml").readText(Charsets.UTF_8)
    private fun paths(text: String) = Regex("""android:pathData="([^"]+)"""").findAll(text).map { it.groupValues[1] }.toList()

    private val signatureFiles = listOf(
        "ic_launcher_foreground", "ic_launcher_monochrome", "ic_brand_mark", "art_opening_signature", "ic_stat_irl"
    )

    @Test
    fun `every surface draws the same signature and the same dot`() {
        val reference = paths(drawable("ic_launcher_foreground"))
        assertEquals("a line and a dot", 2, reference.size)
        for (name in signatureFiles) {
            assertEquals(name, reference, paths(drawable(name)))
            assertTrue(name, drawable(name).contains("""android:strokeWidth="5.4""""))
            assertTrue(name, drawable(name).contains("""android:strokeLineCap="round""""))
        }
    }

    @Test
    fun `the signature stays inside the adaptive icon's safe zone`() {
        val numbers = Regex("""-?\d+(\.\d+)?""").findAll(paths(drawable("ic_launcher_foreground")).first())
            .map { it.value.toDouble() }.toList()
        val points = numbers.chunked(2).filter { it.size == 2 }
        val farthest = points.maxOf { (x, y) -> hypot(x - 54.0, y - 54.0) } + 5.4 / 2
        assertTrue("farthest point $farthest", farthest <= 33.0)
    }

    @Test
    fun `nothing of the Tamil letter mark or the old Meet is left`() {
        val tamilMarkStart = "M37.44,40.85"
        res.walkTopDown().filter { it.isFile && it.extension == "xml" }.forEach { file ->
            val text = file.readText(Charsets.UTF_8)
            assertFalse(file.path, text.contains(tamilMarkStart))
            assertFalse(file.path, text.contains("art_opening_meet"))
        }
        assertFalse(File(res, "drawable/art_opening_meet.xml").exists())
        assertFalse(File(res, "layout/fragment_splash.xml").readText().contains("openingWordmark"))
    }

    @Test
    fun `notifications use the signature, emergencies the siren, and no shield is shown outside the app`() {
        val sources = File(main, "java").walkTopDown().filter { it.isFile && it.extension == "kt" }
            .associate { it.path.replace('\\', '/') to it.readText(Charsets.UTF_8) }
        for ((path, text) in sources) {
            Regex("""setSmallIcon\(R\.drawable\.(\w+)\)""").findAll(text).forEach { match ->
                assertTrue("$path uses ${match.groupValues[1]}", match.groupValues[1] in setOf("ic_stat_irl", "ic_stat_sos"))
            }
            assertFalse(path, text.contains("ic_shield_alert"))
        }
        val tracking = sources.entries.first { it.key.endsWith("core/tracking/SafetyTrackingService.kt") }.value
        assertTrue("your own SOS shows the siren", tracking.contains("R.drawable.ic_stat_sos") && !tracking.contains("ic_stat_irl"))
        val reminders = sources.entries.first { it.key.endsWith("safewalk/session/AndroidSafeWalkAlerts.kt") }.value
        assertTrue(reminders.contains("setSmallIcon(R.drawable.ic_stat_irl)"))
        assertTrue("the SOS and Call 112 actions carry the siren", reminders.contains("addAction(R.drawable.ic_stat_sos"))
        val manifest = File(main, "AndroidManifest.xml").readText()
        assertTrue("the SOS tile shows the siren", manifest.contains("""android:icon="@drawable/ic_stat_sos""""))
        // Small icons are alpha-only: white on transparent, no tint attribute.
        for (name in listOf("ic_stat_irl", "ic_stat_sos")) {
            val icon = drawable(name)
            assertFalse(name, icon.contains("android:tint"))
            Regex("""android:(fill|stroke)Color="([^"]+)"""").findAll(icon).forEach { assertEquals(name, "#FFFFFFFF", it.groupValues[2]) }
        }
    }

    @Test
    fun `the opening writes the signature, lands the dot, then the motto`() {
        val art = drawable("art_opening_signature")
        assertTrue(art.contains("""android:propertyName="trimPathEnd""""))
        assertTrue("the dot starts out there, top right", art.contains("""android:translateX="26"""") && art.contains("""android:translateY="-22""""))
        val layout = File(res, "layout/fragment_splash.xml").readText()
        assertTrue(layout.contains("@drawable/art_opening_signature"))
        for (id in listOf("openingLinePeople", "openingLinePlans", "openingLineLife")) assertTrue(id, layout.contains("@+id/$id"))
        assertTrue("one description for TalkBack", layout.contains("@string/opening_brand_spoken"))
        val viewModel = File(main, "java/com/safesphere/app/feature/splash/ui/SplashViewModel.kt").readText()
        assertTrue("the dot has landed before a returning person is taken on", viewModel.contains("SPLASH_MINIMUM_DURATION_MS = 1100L"))
    }
}
