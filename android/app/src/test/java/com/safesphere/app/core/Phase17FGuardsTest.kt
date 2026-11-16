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
        "sos_options_subtitle", "sos_options_shake_body", "sos_options_shake_no_sensor", "sos_options_shake_no_notifications",
        "sos_options_voice_notice", "shake_countdown_text", "shake_started_text",
        "walk_line_caption", "walk_line_cd", "walk_line_cd_inside", "walk_line_cd_outside",
        "location_last_known"
    )

    @Test
    fun `voice SOS is said plainly to be unavailable, with no coming soon and no promise`() {
        val voice = string("sos_options_voice_notice").lowercase()
        assertTrue(voice.contains("not available"))
        assertFalse(voice.contains("coming soon"))
        assertFalse(voice.contains("will be") || voice.contains("soon"))
        assertTrue("it names what does work", voice.contains("shaking") && voice.contains("sos button"))
    }

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
        val options = File(main, "res/layout/fragment_sos_options.xml").readText(Charsets.UTF_8)
        val sos = File(main, "res/layout/fragment_emergency.xml").readText(Charsets.UTF_8)
        for ((name, text) in listOf("Safety hub" to hub, "SOS options" to options, "SOS screen" to sos)) {
            assertFalse("$name still mentions audio", text.contains("audio", ignoreCase = true))
            assertFalse("$name still mentions evidence", text.contains("evidence", ignoreCase = true))
        }
        assertFalse(manifest.contains("RECORD_AUDIO"))
        assertFalse(manifest.contains("microphone", ignoreCase = true))
        assertFalse(File(main, "java/com/safesphere/app/feature/evidence").exists())
        val recorders = kotlinFiles("").filter { text -> listOf("MediaRecorder", "AudioRecord").any { it in text.readText(Charsets.UTF_8) } }
        assertEquals("no code can record sound", emptyList<String>(), recorders.map { it.name })
        for (text in listOf(string("emergency_limits_notice"), string("sos_options_subtitle"), string("sos_options_hub_description"))) {
            assertFalse(text.contains("audio", ignoreCase = true) && !text.contains("never records", ignoreCase = true))
        }
    }

    @Test
    fun `the shake text promises only a cancellable countdown`() {
        val body = string("sos_options_shake_body").lowercase()
        assertTrue(body.contains("cancel") && body.contains("never sends anything by itself"))
        assertTrue(string("shake_countdown_text").lowercase().contains("cancel"))
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
    fun `no new permission, and the shake adds no service, no boot receiver and no camera`() {
        val permissions = Regex("<uses-permission android:name=\"([^\"]+)\"").findAll(manifest).map { it.groupValues[1] }.toSet()
        assertEquals(
            setOf(
                "INTERNET", "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "POST_NOTIFICATIONS", "FOREGROUND_SERVICE",
                "FOREGROUND_SERVICE_LOCATION", "ACCESS_NETWORK_STATE", "SEND_SMS"
            ).map { "android.permission.$it" }.toSet(),
            permissions
        )
        assertFalse(manifest.contains("BOOT_COMPLETED"))
        val services = Regex("<service\\s+android:name=\"([^\"]+)\"").findAll(manifest).map { it.groupValues[1] }.toSet()
        assertEquals(
            "the shake adds no service: the tile and the one tracking service only",
            setOf(".feature.emergency.entry.SosTileService", ".core.tracking.SafetyTrackingService"),
            services
        )
        val receiver = Regex("<receiver\\s+android:name=\"\\.feature\\.emergency\\.trigger\\.ShakeCountdownCancelReceiver\"\\s+android:exported=\"false\"")
        assertTrue("the Cancel receiver is not exported", receiver.containsMatchIn(manifest))
        assertTrue("the one service has the location type only", manifest.contains("android:foregroundServiceType=\"location\""))
        // N3 (A1): ONE file provider, for the ID photo the camera app writes into the cache (no CAMERA permission);
        // not exported, one cache folder (MembershipTextsTest checks its paths).
        val providers = Regex("<provider\\s+android:name=\"([^\"]+)\"\\s+android:authorities=\"([^\"]+)\"\\s+android:exported=\"false\"")
            .findAll(manifest).map { it.groupValues[1] to it.groupValues[2] }.toList()
        assertEquals("only the ID-photo provider", listOf("androidx.core.content.FileProvider" to "\${applicationId}.idphoto"), providers)
        assertEquals(1, Regex("<provider").findAll(manifest).count())
    }

    @Test
    fun `the accelerometer is registered in exactly one place, and only the holder starts it`() {
        val registering = kotlinFiles("").filter { "registerListener(" in it.readText(Charsets.UTF_8) }.map { it.name }
        assertEquals(listOf("ShakeSensor.kt"), registering)
        val users = kotlinFiles("").filter { it.name != "ShakeSensor.kt" && Regex("\\.start\\(::onSample\\)|sensor\\.start\\(").containsMatchIn(it.readText(Charsets.UTF_8)) }
        assertEquals(listOf("ShakeSos.kt"), users.map { it.name })
    }

    @Test
    fun `the SOS started notification is private on the lock screen, only the countdown is public`() {
        val notifier = File(main, "java/com/safesphere/app/feature/emergency/trigger/AndroidShakeCountdownNotifier.kt")
            .readText(Charsets.UTF_8)
        val started = notifier.substringAfter("override fun showStarted()").substringBefore("override fun clear()")
        assertTrue(started.contains("VISIBILITY_PRIVATE") && started.contains("setPublicVersion"))
        assertFalse("nothing about an SOS may be public after it started", started.contains("VISIBILITY_PUBLIC"))
        val countdown = notifier.substringAfter("override fun showCountdown").substringBefore("override fun showStarted()")
        assertTrue("Cancel must work from the lock screen", countdown.contains("VISIBILITY_PUBLIC") && countdown.contains("addAction"))
    }

    @Test
    fun `the pure shake and walk-line rules use no Android types`() {
        val pure = listOf(
            "feature/emergency/trigger/ShakePolicy.kt", "feature/emergency/trigger/ShakeArming.kt",
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
            kotlinFiles("feature/safewalk").filter { it.name in setOf("WalkLineView.kt", "WalkLineGeometry.kt") } +
            kotlinFiles("feature/safety").filter { it.name.startsWith("SosOptions") }
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
