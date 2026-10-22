package com.safesphere.app.feature.rise

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 10B language rules (docs/PHASE10_DESIGN.md §13) over EVERY Rise text (strings and plurals), and Rise's
 * isolation: phone-only (no network, notification, service, alarm, job) and no reading of SAFE data.
 */
class RiseTextsTest {

    private val main = File("src/main/java/com/safesphere/app")

    @Test
    fun `no Rise text names gender, an opponent, pressure, an obligation or a claim that something is safe`() {
        val texts = riseTexts()
        assertTrue("expected the Rise texts, found ${texts.size}", texts.size > 120)
        val forbidden = mapOf(
            "gender" to Regex("\\b(women|woman|girls?|men|man|male|female|lady|ladies|feminine|masculine)\\b"),
            "opponent" to Regex("\\b(them|they say|prove|show them|haters?|critics?)\\b"),
            "pressure" to Regex("\\b(brave|braver|fearless|no excuses|push yourself|conquer|level up|levels?|streaks?|challenge|points|badges?|score)\\b"),
            "obligation" to Regex("\\b(should|must|have to|need to)\\b"),
            "safety claim" to Regex("\\b(safe|safer|safest|guaranteed?)\\b")
        )
        for ((name, text) in texts) {
            // Product names are not claims: "Safe Walk" is a feature; "no streaks, no scores" says what Rise does NOT do.
            val lower = text.lowercase().replace("safe walk", "").replace("no streaks", "").replace("no scores", "")
            for ((rule, pattern) in forbidden) {
                assertFalse("$name breaks the $rule rule: $text", pattern.containsMatchIn(lower))
            }
        }
    }

    @Test
    fun `suggested steps never make removing support the next level`() {
        val templates = riseTexts().filterKeys { it.startsWith("rise_template_") }
        assertTrue(templates.size >= 20)
        for ((name, text) in templates) {
            val lower = text.lowercase()
            for (word in listOf("on your own", "alone", "without", "by yourself", "no help", "harder", "bigger")) {
                assertFalse("$name: $text", word in lower)
            }
        }
    }

    @Test
    fun `not going ahead is the person's call, support is named as strength, and the safety worry is respected`() {
        val texts = riseTexts()
        assertEquals("That was your call.", texts["rise_after_not_ahead"])
        assertTrue(texts.getValue("rise_supports_note").contains("Choosing support is part of doing something well"))
        assertTrue(texts.getValue("rise_concern_note").contains("worth listening to"))
        assertEquals("That wasn\\'t your fault.", texts["rise_not_ok_title"])
        assertTrue(texts.getValue("rise_tagline").contains("You decide what"))
        assertTrue("the intro says where the data lives", texts.getValue("rise_intro_private").contains("stays on this phone"))
        assertTrue(texts.getValue("rise_intro_no_pressure").contains("Not trying is always fine"))
    }

    @Test
    fun `the SMS texts name no goal, place, date or domain`() {
        val texts = riseTexts()
        for (key in listOf("rise_sms_before", "rise_sms_after")) {
            val text = texts.getValue(key)
            assertFalse(key, Regex("%|\\d").containsMatchIn(text))
            assertTrue(text.length < 70)
        }
    }

    @Test
    fun `Rise never reads SAFE data and SAFE never reads Rise`() {
        val rise = File(main, "feature/rise").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(rise.size >= 10)
        val safePackages = listOf("emergency", "safewalk", "circlealert", "safetycircle", "evidence", "community",
            "intelligence", "moderation", "account", "auth")
        for (file in rise) {
            val text = file.readText(Charsets.UTF_8)
            for (pkg in safePackages) {
                assertFalse("${file.name} imports feature.$pkg", text.contains("com.safesphere.app.feature.$pkg"))
            }
        }
        val others = main.walkTopDown().filter { it.isFile && it.extension == "kt" && !it.path.replace('\\', '/').contains("feature/rise/") }
        for (file in others) {
            if (file.name == "AppContainer.kt") continue // the composition root creates Rise's holders
            assertFalse("${file.name} reads Rise", file.readText(Charsets.UTF_8).contains("com.safesphere.app.feature.rise"))
        }
    }

    @Test
    fun `Rise is phone-only and silent - no network, notification, service, alarm, job or location`() {
        val rise = File(main, "feature/rise").walkTopDown().filter { it.isFile && it.extension == "kt" }.joinToString("\n") {
            it.readText(Charsets.UTF_8)
        }
        for (api in listOf("retrofit", "ApiCallHandler", "okhttp", "NotificationManager", "NotificationCompat", "Service",
                "AlarmManager", "JobScheduler", "WorkManager", "BroadcastReceiver", "LocationProvider", "SmsManager")) {
            assertFalse("Rise uses $api", rise.contains(api))
        }
        // The only way out is the person's own SMS app (no number is filled in).
        assertTrue(rise.contains("Uri.parse(\"smsto:\")"))
    }

    private fun riseTexts(): Map<String, String> {
        val xml = File("src/main/res/values/strings.xml").readText(Charsets.UTF_8)
        val strings = STRING.findAll(xml).associate { it.groupValues[1] to it.groupValues[2] }
        val plurals = PLURALS.findAll(xml).associate { match ->
            match.groupValues[1] to ITEM.findAll(match.groupValues[2]).joinToString(" ") { it.groupValues[1] }
        }
        return (strings + plurals).filterKeys { it.startsWith("rise_") || it == "nav_rise" || it.startsWith("title_rise_") }
    }

    private companion object {
        val STRING = Regex("<string name=\"([a-z0-9_]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
        val PLURALS = Regex("<plurals name=\"([a-z0-9_]+)\">(.*?)</plurals>", RegexOption.DOT_MATCHES_ALL)
        val ITEM = Regex("<item quantity=\"[a-z]+\">(.*?)</item>", RegexOption.DOT_MATCHES_ALL)
    }
}
