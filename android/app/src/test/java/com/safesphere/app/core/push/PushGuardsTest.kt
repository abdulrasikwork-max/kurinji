package com.safesphere.app.core.push

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * N7 (D26, D27, D32, D34): guards on the push code. The merged manifest has exactly the permissions of the design
 * (Firebase adds two install-time ones); IRL's own push components are not exported; Firebase is push only; core/push
 * imports no feature; notification words carry no number and no "what they wrote" by default.
 */
class PushGuardsTest {

    private val main = File("src/main")
    private val pushSources = File(main, "java/com/safesphere/app/core/push").listFiles()!!.filter { it.extension == "kt" }

    @Test
    fun `the merged manifest permissions are the N6 list plus exactly Firebase's two`() {
        val path = System.getProperty("irl.mergedManifest")
        assertTrue("the build passes the merged manifest's path", path != null)
        val merged = File(path!!).readText()
        val declared = Regex("""<uses-permission\s+android:name="([^"]+)"""").findAll(merged).map { it.groupValues[1] }.toSet()
        assertEquals(setOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_LOCATION",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.WAKE_LOCK",
            "com.google.android.c2dm.permission.RECEIVE",
            // androidx.core's signature-level permission for IRL's own unexported receivers (not a user permission)
            "com.safesphere.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
        ), declared)
        assertFalse("Firebase's automatic start-up is removed", merged.contains("FirebaseInitProvider"))
        assertFalse("no analytics", merged.contains("com.google.android.gms.measurement"))
    }

    @Test
    fun `IRL's push service and tap router are not exported, auto-init and analytics are off`() {
        val manifest = File(main, "AndroidManifest.xml").readText()
        for (name in listOf(".core.push.NotificationRouterActivity", ".core.push.IrlMessagingService")) {
            val block = Regex("""android:name="${Regex.escape(name)}"[^>]*>""", RegexOption.DOT_MATCHES_ALL)
                .find(manifest)?.value.orEmpty()
            assertTrue("$name not exported", block.contains("""android:exported="false""""))
        }
        assertTrue(Regex("""firebase_messaging_auto_init_enabled"\s+android:value="false"""").containsMatchIn(manifest))
        assertTrue(Regex("""firebase_analytics_collection_deactivated"\s+android:value="true"""").containsMatchIn(manifest))
        assertTrue(Regex("""delivery_metrics_exported_to_big_query_enabled"\s+android:value="false"""").containsMatchIn(manifest))
    }

    @Test
    fun `Firebase is push delivery only`() {
        // Declarations only (the comments name what is NOT used).
        val build = (File("build.gradle.kts").readLines() + File("../gradle/libs.versions.toml").readLines())
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("#") }
            .joinToString("\n")
        assertTrue(build.contains("firebase-messaging"))
        for (forbidden in listOf("analytics", "crashlytics", "firebase-config", "inappmessaging", "firestore",
            "firebase-auth", "google-services")) {
            assertFalse("no $forbidden", build.lowercase().contains(forbidden))
        }
    }

    @Test
    fun `core push imports no feature and never logs`() {
        for (file in pushSources) {
            val text = file.readText()
            assertFalse("${file.name} imports a feature", Regex("""import com\.safesphere\.app\.feature\.""").containsMatchIn(text))
            assertFalse("${file.name} logs", Regex("""\bLog\.[deiwv]\(|println\(""").containsMatchIn(text))
        }
    }

    @Test
    fun `the payload is read only for t, s, m and v`() {
        val payload = File(main, "java/com/safesphere/app/core/push/PushPayload.kt").readText()
        val keys = Regex("""data\["([a-z]+)"]""").findAll(payload).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("t", "s", "m", "v"), keys)
    }

    @Test
    fun `notification words have no numbers and what people wrote needs the switch`() {
        val strings = File(main, "res/values/strings.xml").readText()
        val push = Regex("""<string name="(push_[a-z_]+)">([^<]*)</string>""").findAll(strings).toList()
        assertTrue(push.size >= 30)
        for (m in push) {
            val words = m.groupValues[2].replace(Regex("%\\d\\\$s"), "")
            assertFalse("${m.groupValues[1]} has a count", words.contains("%d") || Regex("\\d").containsMatchIn(words))
        }
        val replies = File(main, "java/com/safesphere/app/feature/replies/push/RepliesPushRenderers.kt").readText()
        assertTrue("words only behind the switch", replies.contains("if (!showContent()) return null"))
        assertTrue("never collapsed words", replies.contains("!it.collapsed"))
        val tray = File(main, "java/com/safesphere/app/core/push/AndroidPushTray.kt").readText()
        assertTrue("a neutral lock-screen version", tray.contains("setPublicVersion"))
        assertTrue("private on the lock screen", tray.contains("VISIBILITY_PRIVATE"))
        assertFalse(tray.contains("VISIBILITY_PUBLIC"))
    }

    @Test
    fun `a sign-out removes only IRL's push notifications, never the SOS service's`() {
        val tray = File(main, "java/com/safesphere/app/core/push/AndroidPushTray.kt").readText()
        assertFalse(tray.contains("cancelAll()\n") && tray.contains("manager.cancelAll()"))
        assertTrue(tray.contains("it.tag == TAG"))
    }
}
