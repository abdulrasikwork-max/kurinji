package com.safesphere.app.feature.settings

import com.safesphere.app.R
import com.safesphere.app.core.network.SessionEndReason
import com.safesphere.app.core.ui.sessionEndTexts
import com.safesphere.app.feature.community.domain.CommunityPolicy
import com.safesphere.app.feature.community.ui.CommunityTexts
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 10A.1: the honesty, identity and language pass (docs/PHASE10.md). Onboarding describes this build as it is;
 * Settings, Profile, Safety Center and Home have no "Coming soon" placeholders; the agreed identity lines are used; no
 * text tells people to avoid places; the area summary leads with safe places.
 */
class HonestyTextsTest {

    @Test
    fun `onboarding says what this build does, without stale or promising words`() {
        val strings = allStrings()
        val onboarding = strings.filterKeys { it.startsWith("onboarding_") }
        assertEquals(6, onboarding.size)
        for ((name, text) in onboarding) {
            val lower = text.lowercase()
            for (word in listOf("not available", "future", "later", "will offer", "will bring", "being built",
                    "keeps you safe", "stay safe", "guarantee", "protect you")) {
                assertFalse("$name contains \"$word\": $text", word in lower)
            }
        }
        val all = onboarding.values.joinToString(" ").lowercase()
        // Phase 13F (F19): the Kurinji identity, no safety pitch; the SOS honesty line lives in the Safety hub now
        // (SafetyHubTextsTest), and onboarding says where the safety tools are.
        assertTrue(all.contains("written for the next one who goes"))
        assertTrue("the Cocoon is private", all.contains("nobody sees what is in your cocoon"))
        assertTrue("no-tracking promise", all.contains("nobody can use kurinji to track you"))
        assertTrue("where the safety tools live", all.contains("trusted people and sos live in you"))
        for (pitch in listOf("emergency", "danger", "protect", "stay safe")) assertFalse("safety pitch: $pitch", pitch in all)
    }

    @Test
    fun `Settings, You and the Safety hub have no Coming-soon placeholders`() {
        val sources = listOf(
            "feature/settings/ui/SettingsFragment.kt", "feature/profile/ui/YouFragment.kt",
            "feature/safety/ui/SafetyFragment.kt"
        ).map { File("src/main/java/com/safesphere/app/$it") }
        for (file in sources) {
            assertTrue("missing ${file.path}", file.isFile)
            val text = file.readText(Charsets.UTF_8)
            for (stale in listOf("isComingSoon = true", "BadgeStatus.COMING_SOON", "showComingLaterDialog", "comingSoon(")) {
                assertFalse("${file.name} still has $stale", stale in text)
            }
        }
        assertFalse(File("src/main/java/com/safesphere/app/core/ui/PreviewDialogs.kt").exists())
        assertFalse("feature/map was a dead placeholder", File("src/main/java/com/safesphere/app/feature/map").exists())
        val settings = File("src/main/java/com/safesphere/app/feature/settings/ui/SettingsFragment.kt").readText(Charsets.UTF_8)
        // Every row has an action: a Row without a trailing lambda would be a dead row.
        val rows = Regex("Row\\(R\\.string\\.[a-z_]+\\)( \\{)?").findAll(settings).toList()
        assertTrue(rows.size >= 9)
        rows.forEach { assertTrue("dead settings row: ${it.value}", it.value.endsWith("{")) }
    }

    @Test
    fun `the Safety hub opens each tool once, and the retired Home screen is gone`() {
        val hub = File("src/main/java/com/safesphere/app/feature/safety/ui/SafetyFragment.kt").readText(Charsets.UTF_8)
        for (destination in listOf("R.id.emergencyFragment", "R.id.safeWalkFragment", "R.id.safetyCircleFragment",
                "R.id.evidenceVaultFragment", "R.id.communityFragment", "R.id.emergencyHistoryFragment")) {
            assertTrue(destination, hub.contains(destination))
        }
        // One row per tool (the Ready check may also link to the Safety Circle from its own row).
        for (title in listOf("feature_safe_walk_title", "feature_safety_circle_title", "feature_evidence_vault_title",
                "safety_hub_nearby_title", "feature_emergency_history_title")) {
            assertEquals(title, 1, Regex("R\\.string\\.$title\\b").findAll(hub).count())
        }
        assertFalse(File("src/main/res/layout/fragment_home.xml").exists())
        assertFalse(File("src/main/java/com/safesphere/app/feature/home").exists())
    }

    @Test
    fun `identity and language lines are in place`() {
        val strings = allStrings()
        assertEquals("Nearby", strings["nav_community"])
        assertTrue(strings.getValue("feature_safety_circle_description").contains("choose to keep in the loop"))
        assertTrue(strings.getValue("safety_circle_empty_message").contains("choose to keep in the loop"))
        assertTrue(strings.getValue("safe_walk_setup_body").contains("Using support is a choice, not a weakness."))
        assertEquals("Written for the next one who goes.", strings["motto"])
        assertEquals("Kurinji", strings["app_name"])
        assertFalse(strings.values.any { it.contains("Safety, connected") })
        assertTrue(strings.getValue("privacy_policy_body").contains("not legal advice"))
        assertFalse(strings.getValue("privacy_policy_body").contains("placeholder"))
        assertFalse(strings.getValue("logout_dialog_message").contains("preview"))
    }

    @Test
    fun `no text tells people to avoid places or stay away`() {
        val problems = allStrings().filter { (_, text) ->
            val lower = text.lowercase()
            listOf("avoid", "stay away", "don\\'t go", "do not go", "stay home").any { it in lower }
        }.keys
        assertTrue("strings that tell people to avoid places: $problems", problems.isEmpty())
    }

    @Test
    fun `the area summary leads with where you can go`() {
        val parts = CommunityTexts.briefParts(CommunityPolicy.AreaBrief(incidents = 1, conditions = 2, havens = 3, nearestHavenMeters = 120))
        assertEquals(listOf(R.plurals.brief_havens to 3, R.plurals.brief_incidents to 1, R.plurals.brief_conditions to 2), parts)
        val noHavens = CommunityTexts.briefParts(CommunityPolicy.AreaBrief(1, 0, 0, null))
        assertEquals(listOf(R.plurals.brief_incidents to 1), noHavens) // nothing is hidden, zeros are left out
    }

    @Test
    fun `the sign-out dialog says why`() {
        assertEquals(R.string.session_password_changed, sessionEndTexts(SessionEndReason.CREDENTIALS_CHANGED).second)
        assertEquals(R.string.session_account_deleted, sessionEndTexts(SessionEndReason.ACCOUNT_DELETED).second)
        assertEquals(R.string.error_session_expired, sessionEndTexts(null).second)
        assertEquals(R.string.error_session_expired, sessionEndTexts(SessionEndReason.EXPIRED).second)
    }

    private fun allStrings(): Map<String, String> {
        val xml = File("src/main/res/values/strings.xml").readText(Charsets.UTF_8)
        return STRING.findAll(xml).associate { it.groupValues[1] to it.groupValues[2] }
    }

    private companion object {
        val STRING = Regex("<string name=\"([a-z0-9_]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
    }
}
