package com.safesphere.app.core

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 17F guards: what the new SOS options, the shake gesture, the Nearby report, the radar and the Safe Walk line
 * must keep saying and must keep out of. Source and resource scans, like the other wording guards.
 */
class Phase17FGuardsTest {

    private val main = File("src/main")
    private val strings = File("src/main/res/values/strings.xml").readText(Charsets.UTF_8)
    private val manifest = File("src/main/AndroidManifest.xml").readText(Charsets.UTF_8)

    private fun string(name: String): String =
        Regex("<string name=\"$name\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL).find(strings)?.groupValues?.get(1)
            ?: error("string $name not found")

    private fun kotlinFiles(dir: String) =
        File(main, "java/com/safesphere/app/$dir").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private val newStrings = listOf(
        "walk_line_caption", "walk_line_cd", "walk_line_cd_inside", "walk_line_cd_outside",
        "location_last_known"
    )

    @Test
    fun `no new text claims a place, a walk or a person is safe`() {
        for (name in newStrings) {
            val text = string(name).lowercase()
            assertFalse("$name claims safety", Regex("\\b(is|are|it's|you're) safe\\b|\\bsafest\\b|\\bguarantee").containsMatchIn(text))
        }
    }

    /** Phase N1: audio in an SOS left with the Evidence Vault. Nothing may ask for it, offer it or be able to record. */
    @Test
    fun `nothing asks about, offers or can record audio in an SOS`() {
        val hub = File(main, "java/com/safesphere/app/feature/safety/ui/SafetyFragment.kt").readText(Charsets.UTF_8)
        val sos = File(main, "res/layout/fragment_emergency.xml").readText(Charsets.UTF_8)
        for ((name, text) in listOf("Safety hub" to hub, "SOS screen" to sos)) {
            assertFalse("$name still mentions audio", text.contains("audio", ignoreCase = true))
            assertFalse("$name still mentions evidence", text.contains("evidence", ignoreCase = true))
        }
        assertFalse(manifest.contains("RECORD_AUDIO"))
        assertFalse(manifest.contains("microphone", ignoreCase = true))
        assertFalse(File(main, "java/com/safesphere/app/feature/evidence").exists())
        val recorders = kotlinFiles("").filter { text -> listOf("MediaRecorder", "AudioRecord").any { it in text.readText(Charsets.UTF_8) } }
        assertEquals("no code can record sound", emptyList<String>(), recorders.map { it.name })
        for (text in listOf(string("emergency_limits_notice"))) {
            assertFalse(text.contains("audio", ignoreCase = true) && !text.contains("never records", ignoreCase = true))
        }
    }

    @Test
    fun `the Safe Walk line says it is a straight line and not a route`() {
        val caption = string("walk_line_caption").lowercase()
        assertTrue(caption.contains("straight line") && caption.contains("not a route"))
        assertTrue(string("walk_line_cd").lowercase().contains("not a route"))
    }

    @Test
    fun `an old position is never presented as where the person is now`() {
        assertTrue(string("location_last_known").lowercase().contains("last known"))
        assertTrue(string("location_last_known").lowercase().contains("not necessarily where you are now"))
    }

    @Test
    fun `the Safe Walk line places its words with the pure helper, not by pushing them back inside the view`() {
        val line = File(main, "java/com/safesphere/app/feature/safewalk/ui/WalkLineView.kt").readText(Charsets.UTF_8)
        assertTrue(line.contains("WalkLineLabels.place("))
        assertFalse("the old clamp that put a word on its own mark", line.contains("coerceIn(dp(2).toFloat(), width - textWidth"))
    }

    @Test
    fun `no new permission, no extra service, no boot receiver and no camera, and since N6 no SMS permission`() {
        val permissions = Regex("<uses-permission android:name=\"([^\"]+)\"").findAll(manifest).map { it.groupValues[1] }.toSet()
        assertEquals(
            setOf(
                "INTERNET", "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "POST_NOTIFICATIONS", "FOREGROUND_SERVICE",
                "FOREGROUND_SERVICE_LOCATION", "ACCESS_NETWORK_STATE"
            ).map { "android.permission.$it" }.toSet(),
            permissions
        )
        assertFalse(manifest.contains("BOOT_COMPLETED"))
        val services = Regex("<service\\s+android:name=\"([^\"]+)\"").findAll(manifest).map { it.groupValues[1] }.toSet()
        assertEquals(
            "the tile and the one tracking service only",
            setOf(".feature.emergency.entry.SosTileService", ".core.tracking.SafetyTrackingService"),
            services
        )
        assertFalse("N6: the shake Cancel receiver and the SMS result receiver are gone",
            manifest.contains("ShakeCountdownCancelReceiver") || manifest.contains("SmsResultReceiver"))
        assertTrue("the one service has the location type only", manifest.contains("android:foregroundServiceType=\"location\""))
        // N3 (A1), N4 (A2): ONE file provider, for the photos the camera app writes into the cache (the ID photo and a
        // moment's photo; no CAMERA permission); not exported, two cache folders (MembershipTextsTest checks its paths).
        val providers = Regex("<provider\\s+android:name=\"([^\"]+)\"\\s+android:authorities=\"([^\"]+)\"\\s+android:exported=\"false\"")
            .findAll(manifest).map { it.groupValues[1] to it.groupValues[2] }.toList()
        assertEquals("only the camera-photo provider", listOf("androidx.core.content.FileProvider" to "\${applicationId}.camera"), providers)
        // A2: package visibility for the camera intent only (to hide the button without a camera app), nothing else.
        val queries = manifest.substringAfter("<queries>", "").substringBefore("</queries>")
        assertEquals(listOf("android.media.action.IMAGE_CAPTURE"),
            Regex("<action android:name=\"([^\"]+)\"").findAll(queries).map { it.groupValues[1] }.toList())
        assertFalse(manifest.contains("QUERY_ALL_PACKAGES"))
        assertEquals(1, Regex("<queries>").findAll(manifest).count())
        assertEquals(1, Regex("<provider").findAll(manifest).count())
    }

    @Test
    fun `N6 - nothing registers a sensor listener any more (shake SOS is gone)`() {
        val registering = kotlinFiles("").filter { "registerListener(" in it.readText(Charsets.UTF_8) }.map { it.name }
        assertEquals(emptyList<String>(), registering)
    }

    @Test
    fun `the pure walk-line rules use no Android types`() {
        val pure = listOf(
            "feature/safewalk/domain/WalkLineGeometry.kt", "feature/safewalk/domain/WalkLineLabels.kt"
        )
        for (path in pure) {
            val text = File(main, "java/com/safesphere/app/$path").readText(Charsets.UTF_8)
            assertFalse("$path must stay pure Kotlin", text.contains("import android."))
        }
    }

    @Test
    fun `the new SOS and Safe Walk parts stay apart from intelligence and social features`() {
        // (The Safe Walk set-up screen already uses "Check the way" from intelligence since Phase 8D; only the
        // parts added here and the SOS side as a whole are held to the stricter rule.)
        val files = kotlinFiles("feature/emergency") + kotlinFiles("feature/circlealert") +
            kotlinFiles("feature/safewalk").filter { it.name in setOf("WalkLineView.kt", "WalkLineGeometry.kt") }
        for (file in files) {
            val text = file.readText(Charsets.UTF_8)
            assertFalse("${file.name} must not import intelligence", text.contains("import com.safesphere.app.feature.intelligence"))
            assertFalse("${file.name} must not import social", text.contains("import com.safesphere.app.feature.social"))
        }
    }

    @Test
    fun `the Safe Walk line reads no location stream and keeps no trail`() {
        val view = File(main, "java/com/safesphere/app/feature/safewalk/ui/WalkLineView.kt").readText(Charsets.UTF_8)
        for (forbidden in listOf("LocationProvider", "DataStore", "SharedPreferences", "java.io", "okhttp", "retrofit")) {
            assertFalse("the line view must not use $forbidden", view.contains(forbidden))
        }
    }
}
