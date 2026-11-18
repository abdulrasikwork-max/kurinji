package com.safesphere.app.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * N6 (docs/PHASE_N6.md §4 and §7): the phone never texts anyone by itself (D20: the server does), there is no shake SOS
 * (D21), the permission list is exactly the one in the design, and the SOS countdown never asks for a permission.
 */
class N6SafetyGuardsTest {

    private val main = File("src/main")
    private val sources = File(main, "java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun `the permissions are exactly the N6 list`() {
        val manifest = File(main, "AndroidManifest.xml").readText()
        val declared = Regex("""<uses-permission\s+android:name="([^"]+)"""").findAll(manifest).map { it.groupValues[1] }.toSet()
        assertEquals(setOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_LOCATION",
            "android.permission.ACCESS_NETWORK_STATE"
        ), declared)
    }

    @Test
    fun `nothing sends an SMS by itself or listens to the motion sensor`() {
        val forbidden = listOf("SmsManager", "SEND_SMS", "sendTextMessage", "sendMultipartTextMessage", "SensorManager",
            "TYPE_ACCELEROMETER", "Sensor.TYPE_")
        val hits = sources.flatMap { file ->
            val text = file.readText()
            forbidden.filter { it in text }.map { "${file.name}: $it" }
        }
        assertTrue("found: $hits", hits.isEmpty())
        assertTrue(File(main, "java/com/safesphere/app/feature/emergency/trigger").let { !it.exists() || it.list().isNullOrEmpty() })
    }

    @Test
    fun `the SOS screen asks for location and starts the service only once the SOS runs, never during the countdown`() {
        val fragment = File(main, "java/com/safesphere/app/feature/emergency/ui/EmergencyFragment.kt").readText()
        assertTrue(fragment.contains("val sosRunning = viewModel.uiState.value is EmergencyUiState.Active"))
        assertTrue(fragment.contains("if (sosRunning && ui.sharing.status == LocationSharingStatus.PERMISSION_NEEDED"))
        assertTrue(fragment.contains("if (sosRunning) serviceStarter.startIfNeeded("))
    }

    @Test
    fun `the SMS app is the only way the phone texts the Circle, and it needs no permission`() {
        val texts = File(main, "java/com/safesphere/app/feature/circlealert/ui/CircleAlertTexts.kt").readText()
        assertTrue(texts.contains("Intent.ACTION_SENDTO"))
    }
}
