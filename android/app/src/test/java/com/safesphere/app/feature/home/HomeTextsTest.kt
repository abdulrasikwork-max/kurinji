package com.safesphere.app.feature.home

import com.safesphere.app.R
import com.safesphere.app.core.ui.components.StatusTone
import com.safesphere.app.feature.home.ui.HomeStatus
import com.safesphere.app.feature.home.ui.HomeStatusCard
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Home's words. The status card states only facts the phone knows, and no text Home shows ever says that a place
 * or a person is safe (docs/PHASE8_DESIGN.md §6.4). Every string referenced by Home's layouts and code is scanned,
 * so a new one cannot slip past. Allowed uses of the word: "Safe Walk" (a feature name), "safe place(s)" (the
 * Phase 6 category for reported havens) and negations ("does not mean it is safe").
 */
class HomeTextsTest {

    @Test
    fun `every visible status has its own words and icon`() {
        val shown = HomeStatus.entries.mapNotNull { HomeStatusCard.contentFor(it) }
        assertEquals(HomeStatus.entries.size - 1, shown.size) // only EMERGENCY is hidden
        assertEquals(shown.size, shown.map { it.labelRes }.toSet().size)
        assertEquals(shown.size, shown.map { it.headlineRes }.toSet().size)
        assertEquals(shown.size, shown.map { it.detailRes }.toSet().size)
        assertEquals(shown.size, shown.map { it.iconRes }.toSet().size)
    }

    @Test
    fun `only a missed check-in is amber, and amber always comes with the warning icon`() {
        for (status in HomeStatus.entries) {
            val content = HomeStatusCard.contentFor(status) ?: continue
            val caution = content.tone == StatusTone.CAUTION
            assertEquals("$status", status == HomeStatus.CHECK_IN_MISSED, caution)
            assertEquals("$status", caution, content.iconRes == R.drawable.ic_warning)
        }
    }

    @Test
    fun `during an SOS the card is hidden, so the red banner is the one emergency element`() {
        assertNull(HomeStatusCard.contentFor(HomeStatus.EMERGENCY))
    }

    @Test
    fun `no Home text claims safety`() {
        val strings = allStrings()
        val names = homeStringNames()
        assertTrue("Home strings not found", names.size >= 20)
        assertTrue("status card strings not scanned", names.containsAll(STATUS_CARD_STRINGS))

        val problems = mutableListOf<String>()
        for (name in names) {
            val text = strings[name]
            if (text == null) {
                problems += "$name is not in strings.xml"
                continue
            }
            val lower = text.lowercase()
            FORBIDDEN.filter { it in lower }.forEach { problems += "$name contains \"$it\"" }
            for (sentence in lower.split(". ")) {
                if (NEGATION.any { it in sentence }) continue
                val rest = ALLOWED.fold(sentence) { acc, phrase -> acc.replace(phrase, "") }
                if (SAFE_WORD.containsMatchIn(rest)) problems += "$name claims safety: \"$sentence\""
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun `Home never shows the Safe badge`() {
        val sources = homeSources().joinToString("\n") { it.readText(Charsets.UTF_8) }
        assertTrue(sources.isNotBlank())
        assertTrue("BadgeStatus.SAFE" !in sources)
        assertTrue("badgeStatus=\"safe\"" !in sources)
    }

    @Test
    fun `the nothing-running status says what Home does not do`() {
        val text = allStrings().getValue("status_idle_detail").lowercase()
        assertTrue(text.contains("does not check"))
        assertTrue(allStrings().getValue("status_idle_headline").contains("on this phone"))
    }

    @Test
    fun `the SOS tile says when the Safety Circle is texted, and never that nobody is alerted`() {
        val strings = allStrings()
        val description = strings.getValue("cd_sos_tile").lowercase()
        val badge = strings.getValue("sos_badge_countdown").lowercase()
        // Phase 4D: SafeSphere texts the Safety Circle only with automatic SMS switched on.
        assertTrue(description.contains("only if you switched on automatic sms"))
        assertTrue(description.contains("cancel"))
        assertTrue(badge.contains("cancel"))
        for (text in listOf(description, badge)) {
            STALE_SOS.forEach { assertTrue("SOS tile still says \"$it\": $text", it !in text) }
        }
        assertTrue("sos_badge_countdown" in homeStringNames())
    }

    private fun allStrings(): Map<String, String> {
        val xml = File("src/main/res/values/strings.xml").readText(Charsets.UTF_8)
        return STRING.findAll(xml).associate { it.groupValues[1] to it.groupValues[2] }
    }

    private fun homeStringNames(): Set<String> = homeSources().flatMap { file ->
        val text = file.readText(Charsets.UTF_8)
        val pattern = if (file.extension == "xml") LAYOUT_REF else CODE_REF
        pattern.findAll(text).map { it.groupValues[1] }.toList()
    }.toSet()

    private fun homeSources(): List<File> {
        val layouts = listOf("fragment_home.xml", "view_safety_status_card.xml")
            .map { File("src/main/res/layout/$it") }
        val code = File("src/main/java/com/safesphere/app/feature/home/ui").listFiles { f -> f.extension == "kt" }
            .orEmpty().toList() + File("src/main/java/com/safesphere/app/core/ui/components/SafetyStatusCardView.kt")
        return (layouts + code).onEach { assertTrue("missing ${it.path}", it.isFile) }
    }

    private companion object {
        val STRING = Regex("<string name=\"([a-z0-9_]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
        val LAYOUT_REF = Regex("@string/([a-z0-9_]+)")
        val CODE_REF = Regex("R\\.string\\.([a-z0-9_]+)")
        val STATUS_CARD_STRINGS = listOf(
            "status_overline", "cd_status_card",
            "status_checking_label", "status_checking_headline", "status_checking_detail",
            "status_idle_label", "status_idle_headline", "status_idle_detail",
            "status_walk_label", "status_walk_headline", "status_walk_detail",
            "status_missed_label", "status_missed_headline", "status_missed_detail"
        )
        val FORBIDDEN = listOf("safest", "safe state", "safe route", "safe way", "dangerous", "verified", "guarantee",
            "no risk", "risk-free", "all clear", "danger zone", "secure area")
        val ALLOWED = listOf("safe walk", "safe places", "safe place")
        val STALE_SOS = listOf("no alerts", "not active yet", "nobody is", "no one is")
        val SAFE_WORD = Regex("\\bsafe\\b")
        val NEGATION = listOf("not ", "cannot", "can't", "n't ")
    }
}
