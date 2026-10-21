package com.safesphere.app.feature.intelligence

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 8 safety rule on the phone (docs/PHASE8_DESIGN.md §10): Safety Intelligence is decision support. The code
 * that runs SOS, the tracking service, Safe Walk's check-ins, movement checks and escalation, and the Safety Circle
 * alerts never reads it, so a picture or a brief can never start, stop or change any of them.
 */
class IntelligenceIsolationTest {

    private val main = File("src/main/java/com/safesphere/app")

    private val protected = listOf(
        "feature/emergency",
        "feature/circlealert",
        "feature/evidence",
        "core/tracking",
        "feature/safewalk/session",
        "feature/safewalk/domain/CheckInPolicy.kt",
        "feature/safewalk/domain/MovementPolicy.kt",
        "core/device/BatteryPolicy.kt"
    )

    @Test
    fun `safety features never read Safety Intelligence`() {
        val violations = protected.flatMap { path ->
            val root = File(main, path)
            assertTrue("missing $root", root.exists())
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { file ->
                file.readLines().mapIndexedNotNull { i, line ->
                    if (line.contains("feature.intelligence") || line.contains("CorridorBrief")) "${file.name}:${i + 1}" else null
                }
            }.toList()
        }
        assertTrue("must not reference Safety Intelligence: $violations", violations.isEmpty())
    }

    @Test
    fun `intelligence stores nothing on the phone`() {
        val root = File(main, "feature/intelligence")
        val violations = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { file ->
            file.readLines().mapIndexedNotNull { i, line ->
                if (STORAGE.containsMatchIn(line)) "${file.name}:${i + 1}" else null
            }
        }.toList()
        assertTrue("pictures and briefs live in memory only: $violations", violations.isEmpty())
    }

    private companion object {
        val STORAGE = Regex("DataStore|SharedPreferences|openFileOutput|FileOutputStream|Room|getSharedPreferences")
    }
}
