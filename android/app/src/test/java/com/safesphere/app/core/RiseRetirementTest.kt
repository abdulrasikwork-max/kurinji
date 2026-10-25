package com.safesphere.app.core

import com.safesphere.app.core.retired.RiseRetirement
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 13F (D9): Rise is retired. Its encrypted notes are deleted once, only its own folder is touched, the person is
 * told once, and no Rise code or text is left in the app.
 */
class RiseRetirementTest {

    private fun filesDir(): File = Files.createTempDirectory("files").toFile()

    @Test
    fun `deletes the Rise folder once and asks for the notice once`() {
        val dir = filesDir()
        val rise = File(dir, "rise").apply { mkdirs() }
        File(rise, "rise-7.bin").writeText("ciphertext")
        File(rise, "rise-7.bin.bak").writeText("ciphertext")
        val retirement = RiseRetirement(dir)

        assertTrue(retirement.retire())
        assertFalse(rise.exists())
        assertTrue(retirement.noticePending())
        assertFalse("a second start deletes nothing more", retirement.retire())

        retirement.noticeShown()
        assertFalse(retirement.noticePending())
    }

    @Test
    fun `touches nothing else, and a phone that never used Rise sees no notice`() {
        val dir = filesDir()
        val evidence = File(dir, "evidence").apply { mkdirs() }
        File(evidence, "index.json").writeText("{}")
        File(dir, "recovery-keys.json").writeText("{}")
        val retirement = RiseRetirement(dir)

        assertFalse(retirement.retire())
        assertFalse(retirement.noticePending())
        assertTrue(File(evidence, "index.json").exists())
        assertTrue(File(dir, "recovery-keys.json").exists())
        assertEquals(setOf("evidence", "recovery-keys.json"), dir.list()!!.toSet())
    }

    @Test
    fun `no Rise screen, code or text is left`() {
        assertFalse(File("src/main/java/com/safesphere/app/feature/rise").exists())
        val layouts = File("src/main/res/layout").list()!!.filter { it.contains("rise") }
        assertTrue("Rise layouts left: $layouts", layouts.isEmpty())
        val strings = File("src/main/res/values/strings.xml").readText(Charsets.UTF_8)
        val riseNames = Regex("name=\"(rise_[a-z_]+|nav_rise|title_rise_[a-z_]+)\"").findAll(strings)
            .map { it.groupValues[1] }.toSet()
        assertEquals(setOf("rise_retired_title", "rise_retired_message"), riseNames)
        val graph = File("src/main/res/navigation/nav_graph.xml").readText(Charsets.UTF_8)
        assertFalse(graph.contains("feature.rise"))
    }
}
