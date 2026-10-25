package com.safesphere.app.feature.going

import com.safesphere.app.feature.emergency.entry.SosLaunchRequest
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 13F (docs/PHASE13F_PLAN.md F4, F9, F11): the bridge from reading to going stays a bridge, the Heads-up note
 * carries no location or link, social and safety code never import each other, and only this app's own SOS shortcut
 * or tile can start the countdown.
 */
class GoingThereTest {

    private val main = File("src/main/java/com/safesphere/app")
    private val safety = listOf("emergency", "safewalk", "circlealert", "safetycircle", "evidence", "community",
        "intelligence")

    private fun sources(pkg: String) = File(main, "feature/$pkg").walkTopDown().filter { it.isFile && it.extension == "kt" }
        .toList().also { assertTrue("no sources in $pkg", it.isNotEmpty()) }

    @Test
    fun `the Going there sheet imports neither social nor safety code`() {
        for (file in sources("going")) {
            val text = file.readText(Charsets.UTF_8)
            for (pkg in safety + "social" + "moderation") {
                assertFalse("${file.name} imports feature.$pkg", text.contains("com.safesphere.app.feature.$pkg"))
            }
        }
    }

    @Test
    fun `social code never imports safety code, and safety code never imports social code`() {
        for (file in sources("social")) {
            val text = file.readText(Charsets.UTF_8)
            for (pkg in safety) assertFalse("${file.name} imports feature.$pkg", text.contains("com.safesphere.app.feature.$pkg."))
        }
        for (pkg in safety) for (file in sources(pkg)) {
            assertFalse("${file.name} imports feature.social", file.readText(Charsets.UTF_8).contains("com.safesphere.app.feature.social"))
        }
    }

    @Test
    fun `the Heads-up note names at most a place, never a location or a link, and the sheet says so`() {
        val strings = allStrings()
        for (name in listOf("going_heads_up", "going_heads_up_no_place")) {
            val text = strings.getValue(name).lowercase()
            for (word in listOf("http", "maps", "location", "lat", "geo:", "live")) assertFalse("$name: $text", word in text)
            assertTrue(text.contains("message you when"))
        }
        assertEquals(1, Regex("%1\\\$s").findAll(strings.getValue("going_heads_up")).count())
        val footer = strings.getValue("going_footer").lowercase()
        assertTrue(footer.contains("no location") && footer.contains("no link"))
        assertTrue(footer.contains("sends and keeps nothing"))
        assertTrue(footer.contains("nothing starts until you press"))
    }

    @Test
    fun `the SOS entry is not exported, and only an in-memory request starts the countdown`() {
        val manifest = File("src/main/AndroidManifest.xml").readText(Charsets.UTF_8)
        val entry = manifest.substringAfter("android:name=\".feature.emergency.entry.SosEntryActivity\"").substringBefore("/>")
        assertTrue(entry.contains("android:exported=\"false\""))
        val tile = manifest.substringAfter("android:name=\".feature.emergency.entry.SosTileService\"").substringBefore("</service>")
        assertTrue(tile.contains("android.permission.BIND_QUICK_SETTINGS_TILE"))
        // The auto-start argument is set in exactly one place: after consuming the request.
        val writers = main.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { it.readText(Charsets.UTF_8).contains("ARG_AUTO_START to true") }.map { it.name }.toList()
        assertEquals(listOf("MainActivity.kt"), writers)
    }

    @Test
    fun `an SOS request is used once and expires`() {
        val request = SosLaunchRequest()
        assertFalse(request.consume(1_000))
        request.request(1_000)
        assertTrue(request.consume(1_000 + SosLaunchRequest.VALID_MS))
        assertFalse("used once", request.consume(1_001))
        request.request(1_000)
        assertFalse("too old", request.consume(1_001 + SosLaunchRequest.VALID_MS))
        request.request(5_000)
        assertFalse("a clock that went backwards is not trusted", request.consume(4_000))
    }

    private fun allStrings(): Map<String, String> {
        val xml = File("src/main/res/values/strings.xml").readText(Charsets.UTF_8)
        return Regex("<string name=\"([a-z0-9_]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL).findAll(xml)
            .associate { it.groupValues[1] to it.groupValues[2] }
    }
}
