package com.safesphere.app.core

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 16 (decision U4, the user's choice 2026-10-26): red is the brand now, so emergency UI must never depend on
 * colour. Every emergency surface is the black emergency surface with a red ring, carries the siren and says what it is.
 */
class EmergencyTreatmentTest {

    private val styles = File("src/main/res/values/styles.xml").readText(Charsets.UTF_8)

    private fun style(name: String): String =
        styles.substringAfter("<style name=\"$name\"").substringBefore("</style>")

    @Test
    fun `emergency card and button are black with a red ring, never just red`() {
        for (name in listOf("Widget.SafeSphere.Card.Emergency", "Widget.SafeSphere.Button.Emergency")) {
            val s = style(name)
            val fill = if (name.contains("Card")) "cardBackgroundColor" else "backgroundTint"
            assertTrue("$name fill", s.contains("<item name=\"$fill\">?attr/colorEmergencySurface</item>"))
            assertTrue("$name ring", s.contains("<item name=\"strokeColor\">?attr/colorError</item>"))
            assertTrue("$name ring width", s.contains("<item name=\"strokeWidth\">3dp</item>"))
        }
        assertTrue(style("Widget.SafeSphere.Button.Emergency").contains("@drawable/ic_siren"))
    }

    @Test
    fun `every emergency card shows the siren`() {
        val layouts = File("src/main/res/layout").listFiles()!!.filter { it.readText(Charsets.UTF_8).contains("Card.Emergency") }
        assertTrue(layouts.isNotEmpty())
        for (layout in layouts) {
            val text = layout.readText(Charsets.UTF_8)
            val cards = text.split("Widget.SafeSphere.Card.Emergency").drop(1)
            for (card in cards) {
                val body = card.substringBefore("</com.google.android.material.card.MaterialCardView>")
                assertTrue("${layout.name}: an emergency card without the siren", body.contains("@drawable/ic_siren"))
            }
        }
    }

    @Test
    fun `brand buttons are not dressed as emergencies`() {
        val primary = style("Widget.SafeSphere.Button.Primary")
        assertTrue(!primary.contains("ic_siren") && !primary.contains("colorEmergencySurface"))
    }
}