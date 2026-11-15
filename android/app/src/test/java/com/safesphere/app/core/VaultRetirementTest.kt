package com.safesphere.app.core

import com.safesphere.app.core.retired.VaultRetirement
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase N1: the Evidence Vault is retired. What it kept on the phone is deleted once, only its own folders are touched,
 * the person is told once and only if the vault held something, and no vault code is left in the app.
 */
class VaultRetirementTest {

    private fun dir(name: String): File = Files.createTempDirectory(name).toFile()

    @Test
    fun `deletes the three vault folders once and asks for the notice once`() {
        val files = dir("files")
        val cache = dir("cache")
        val vault = File(files, "evidence").apply { mkdirs() }
        File(vault, "0b0e1c1e-0000-4000-8000-000000000001.ssev").writeText("ciphertext")
        File(vault, "index.json").writeText("{}")
        File(vault, "recovery-keys.json").writeText("{}")
        File(files, "evidence-capture").mkdirs()
        val view = File(cache, "evidence-view").apply { mkdirs() }
        File(view, "plain.m4a").writeText("plain")
        val retirement = VaultRetirement(files, cache)

        assertTrue(retirement.retire())
        assertFalse(vault.exists())
        assertFalse(File(files, "evidence-capture").exists())
        assertFalse(view.exists())
        assertTrue(retirement.noticePending())
        assertFalse("a second start deletes nothing more", retirement.retire())
        assertTrue("the notice survives a second start until it was shown", retirement.noticePending())

        retirement.noticeShown()
        assertFalse(retirement.noticePending())
    }

    @Test
    fun `an unsealed capture counts as something the vault held`() {
        val files = dir("files")
        val capture = File(files, "evidence-capture").apply { mkdirs() }
        File(capture, "part.m4a").writeText("plain audio")
        val retirement = VaultRetirement(files, dir("cache"))

        assertTrue(retirement.retire())
        assertFalse(capture.exists())
        assertTrue(retirement.noticePending())
    }

    @Test
    fun `empty vault folders are removed without a notice`() {
        val files = dir("files")
        val cache = dir("cache")
        val vault = File(files, "evidence").apply { mkdirs() }
        File(vault, "index.json").writeText("{\"items\":[]}")
        File(files, "evidence-capture").mkdirs()
        File(cache, "evidence-view").mkdirs()
        val retirement = VaultRetirement(files, cache)

        assertFalse(retirement.retire())
        assertFalse(vault.exists())
        assertFalse(retirement.noticePending())
        assertEquals(emptySet<String>(), files.list()!!.toSet())
    }

    @Test
    fun `touches nothing else, and a phone that never had the vault sees no notice`() {
        val files = dir("files")
        val cache = dir("cache")
        val places = File(files, "places").apply { mkdirs() }
        File(places, "places-7.bin").writeText("ciphertext")
        File(cache, "image_cache").mkdirs()
        val retirement = VaultRetirement(files, cache)

        assertFalse(retirement.retire())
        assertFalse(retirement.noticePending())
        assertTrue(File(places, "places-7.bin").exists())
        assertEquals(setOf("places"), files.list()!!.toSet())
        assertEquals(setOf("image_cache"), cache.list()!!.toSet())
    }

    @Test
    fun `no vault screen, code or way in is left`() {
        val app = File("src/main/java/com/safesphere/app")
        assertFalse(File(app, "feature/evidence").exists())
        for (gone in listOf("core/crypto/EvidenceCipher.kt", "core/crypto/RecoveryKeyCrypto.kt", "core/ui/ScreenLockGate.kt")) {
            assertFalse(gone, File(app, gone).exists())
        }
        val layouts = File("src/main/res/layout").list()!!.filter { it.contains("evidence") || it.contains("recovery") }
        assertTrue("vault layouts left: $layouts", layouts.isEmpty())
        val graph = File("src/main/res/navigation/nav_graph.xml").readText(Charsets.UTF_8)
        assertFalse(graph.contains("feature.evidence"))
        assertFalse(graph.contains("evidenceVaultFragment"))
        val importing = app.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { it.readText(Charsets.UTF_8).contains("com.safesphere.app.feature.evidence") }.map { it.name }.toList()
        assertEquals(emptyList<String>(), importing)
    }
}
