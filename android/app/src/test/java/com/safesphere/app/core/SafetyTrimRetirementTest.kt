package com.safesphere.app.core

import com.safesphere.app.core.retired.SafetyTrimRetirement
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** N6 (D21): the shake switch kept by the SOS options screen is deleted once; other stores are left alone. */
class SafetyTrimRetirementTest {

    @Test
    fun `the SOS options store is deleted, other stores stay, and a second run has nothing to do`() {
        val files = Files.createTempDirectory("n6-retire").toFile()
        val datastore = File(files, "datastore").apply { mkdirs() }
        val options = File(datastore, "sos_options.preferences_pb").apply { writeText("shake=true") }
        val circle = File(datastore, "circle_alerts.preferences_pb").apply { writeText("kept") }

        assertTrue(SafetyTrimRetirement(files).retire())
        assertFalse(options.exists())
        assertTrue(circle.exists())
        assertTrue(SafetyTrimRetirement(files).retire())
        files.deleteRecursively()
    }
}
