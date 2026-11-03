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
        "sos_options_audio_body", "sos_options_audio_no_permission", "sos_options_audio_denied", "sos_options_voice_notice",
        "audio_question_message", "evidence_recording_started_auto", "shake_countdown_text", "shake_started_text",
        "walk_line_caption", "walk_line_cd", "walk_line_cd_inside", "walk_line_cd_outside", "location_last_known",
        "report_sheet_intro", "radar_legend"
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

    @Test
    fun `the audio question tells the whole truth before anyone agrees`() {
        val question = string("audio_question_message").lowercase()
        for (fact in listOf("encrypted", "notification", "stop it any time", "nothing is recorded outside an sos", "sos options", "microphone")) {
            assertTrue("mentions \"$fact\"", question.contains(fact))
        }
        assertTrue("both answers are explicit", string("audio_question_yes").isNotBlank() && string("audio_question_no") == "No")
        val body = string("sos_options_audio_body").lowercase()
        assertTrue(body.contains("nothing is recorded outside an sos") && body.contains("stop it any time"))
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
    fun `the Nearby report still asks for no text and no photo`() {
        val layout = File("src/main/res/layout/dialog_report_sheet.xml").readText(Charsets.UTF_8)
        for (forbidden in listOf("EditText", "TextInputLayout", "ImageView", "ImageButton")) {
            assertFalse("the report sheet must not contain $forbidden", layout.contains(forbidden))
        }
        assertTrue(layout.contains("report_confirm_message"))
    }

    @Test
    fun `the report sheet keeps Share and Cancel outside the scrolling choices (17F-V5, 2_0x text)`() {
        val layout = File("src/main/res/layout/dialog_report_sheet.xml").readText(Charsets.UTF_8)
        val scrollEnd = layout.indexOf("</androidx.core.widget.NestedScrollView>")
        assertTrue("the choices scroll inside a NestedScrollView", scrollEnd > 0 && layout.indexOf("<androidx.core.widget.NestedScrollView") in 0 until scrollEnd)
        for (id in listOf("reportShareButton", "reportCancelButton")) {
            val at = layout.indexOf("@+id/$id")
            assertTrue("$id exists", at >= 0)
            assertTrue("$id must be in the footer, after the scroll area closes (a clipped Cancel at 2.0x)", at > scrollEnd)
        }
        assertTrue("the choices are inside the scroll area", layout.indexOf("@+id/reportChoices") in 0 until scrollEnd)
        val fragment = File(main, "java/com/safesphere/app/feature/community/ui/CommunityFragment.kt").readText(Charsets.UTF_8)
        val sheet = fragment.substringAfter("private fun showReportSheet()").substringBefore("private fun confirmAnswer")
        assertTrue("a bottom sheet, not a system alert dialog", sheet.contains("BottomSheetDialog(") && !sheet.contains("MaterialAlertDialogBuilder"))
        assertTrue("Share stays off until something is chosen", sheet.contains("reportShareButton.isEnabled = chosen != null"))
    }

    @Test
    fun `a Nearby signal row keeps its badge under the title so its words are not squeezed at 2_0x (17F-V8)`() {
        val row = File("src/main/res/layout/item_area_signal.xml").readText(Charsets.UTF_8)
        val badge = row.indexOf("@+id/signalBadge")
        assertTrue(badge > 0)
        assertTrue("the badge is inside the text column, between the title and the details",
            row.indexOf("@+id/signalTitle") < badge && badge < row.indexOf("@+id/signalDetails"))
        val summary = row.substringAfter("@+id/signalSummary").substringBefore("@+id/signalDisputed")
        assertEquals("only the text column takes the row's spare width", 1, Regex("layout_weight=\"1\"").findAll(summary).count())
    }

    @Test
    fun `the Safe Walk line and the radar place their words with the pure helpers, not by pushing them back inside the view`() {
        val line = File(main, "java/com/safesphere/app/feature/safewalk/ui/WalkLineView.kt").readText(Charsets.UTF_8)
        assertTrue(line.contains("WalkLineLabels.place("))
        assertFalse("the old clamp that put a word on its own mark", line.contains("coerceIn(dp(2).toFloat(), width - textWidth"))
        val radar = File(main, "java/com/safesphere/app/feature/community/ui/SafetyRadarView.kt").readText(Charsets.UTF_8)
        assertTrue(radar.contains("RadarLayout.ringLabelBox(") && radar.contains("RadarLayout.numberSpot(") && radar.contains("keepClear"))
    }

    @Test
    fun `no new permission, and the shake adds no service, no boot receiver and no camera`() {
        val permissions = Regex("<uses-permission android:name=\"([^\"]+)\"").findAll(manifest).map { it.groupValues[1] }.toSet()
        assertEquals(
            setOf(
                "INTERNET", "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "POST_NOTIFICATIONS", "FOREGROUND_SERVICE",
                "FOREGROUND_SERVICE_LOCATION", "ACCESS_NETWORK_STATE", "SEND_SMS", "RECORD_AUDIO", "FOREGROUND_SERVICE_MICROPHONE",
                "USE_BIOMETRIC"
            ).map { "android.permission.$it" }.toSet(),
            permissions
        )
        assertFalse(manifest.contains("BOOT_COMPLETED"))
        val services = Regex("<service\\s+android:name=\"([^\"]+)\"").findAll(manifest).map { it.groupValues[1] }.toSet()
        assertEquals(
            "the shake adds no service: the tile, the one tracking service and the upload job only",
            setOf(
                ".feature.emergency.entry.SosTileService", ".core.tracking.SafetyTrackingService",
                ".feature.evidence.session.EvidenceUploadJobService"
            ),
            services
        )
        val receiver = Regex("<receiver\\s+android:name=\"\\.feature\\.emergency\\.trigger\\.ShakeCountdownCancelReceiver\"\\s+android:exported=\"false\"")
        assertTrue("the Cancel receiver is not exported", receiver.containsMatchIn(manifest))
    }

    @Test
    fun `the accelerometer is registered in exactly one place, and only the holder starts it`() {
        val registering = kotlinFiles("").filter { "registerListener(" in it.readText(Charsets.UTF_8) }.map { it.name }
        assertEquals(listOf("ShakeSensor.kt"), registering)
        val users = kotlinFiles("").filter { it.name != "ShakeSensor.kt" && Regex("\\.start\\(::onSample\\)|sensor\\.start\\(").containsMatchIn(it.readText(Charsets.UTF_8)) }
        assertEquals(listOf("ShakeSos.kt"), users.map { it.name })
    }

    @Test
    fun `the audio question never blocks the way to SOS`() {
        val hub = File(main, "java/com/safesphere/app/feature/safety/ui/SafetyFragment.kt").readText(Charsets.UTF_8)
        assertFalse("the question must be dismissable (Back = ask next time)", hub.contains("setCancelable(false)"))
        assertTrue("not shown while an SOS runs", hub.contains("sosRecorder.state.value.current != null"))
        assertTrue("not shown during a Safe Walk", hub.contains("safeWalk.state.value.isActive"))
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
            "feature/safewalk/domain/WalkLineGeometry.kt", "feature/safewalk/domain/WalkLineLabels.kt", "feature/community/ui/RadarLayout.kt",
            "feature/evidence/domain/AutoAudioPolicy.kt", "feature/community/domain/ReportChoices.kt"
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
