package com.safesphere.app.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 13E, decision D2 (EXPERIENCE_PLATFORM_DESIGN.md §6.1, §11.8): replaces NoMlRuntimeTest. A learned model may run
 * on the phone ONLY as the plain-Kotlin linear model in core/textcheck, with its weights as data under
 * assets/textcheck/ — no ML runtime, no AI-service library. In 13E no model ships at all (E1): the folder holds the
 * hashed lexicon only. The check runs on the phone and keeps drafts there: core/textcheck has no network, storage or
 * logging, and only it may read the lexicon.
 */
class ModelPlacesTest {

    private val textcheck = File("src/main/java/com/safesphere/app/core/textcheck")

    @Test
    fun `no machine learning runtime or AI service library in the app`() {
        val files = listOf(File("../gradle/libs.versions.toml"), File("build.gradle.kts"))
        files.forEach { assertTrue("missing $it", it.exists()) }
        val text = files.joinToString("\n") { it.readText(Charsets.UTF_8) }.lowercase()
        for (runtime in FORBIDDEN) assertFalse("no ML runtime or AI service: $runtime", text.contains(runtime))
    }

    @Test
    fun `model and lexicon files live only in assets textcheck, and 13E ships no model`() {
        val found = File("src/main").walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in MODEL_EXTENSIONS }
            .map { it.relativeTo(File("src/main")).invariantSeparatorsPath }.toList()
        assertEquals(listOf("assets/textcheck/lexicon-v1.keys"), found)
    }

    @Test
    fun `the check keeps drafts on the phone`() {
        assertTrue(textcheck.isDirectory)
        textcheck.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val text = file.readText(Charsets.UTF_8)
            for (forbidden in listOf("import android.", "okhttp", "retrofit", "java.net", "java.io.File", "Log.",
                    "println", "DataStore", "SharedPreferences", "Dispatchers")) {
                assertFalse("${file.name} must not use $forbidden", text.contains(forbidden))
            }
        }
    }

    @Test
    fun `only the container opens the lexicon asset`() {
        val users = File("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { it.readText(Charsets.UTF_8).contains("HashedLexicon.ASSET") }.map { it.name }.toList()
        assertEquals(listOf("AppContainer.kt"), users)
    }

    private companion object {
        val FORBIDDEN = listOf(
            "tensorflow", "tflite", "litert", "mlkit", "mediapipe", "onnx", "pytorch", "djl", "tribuo", "smile",
            "weka", "genai", "openai", "anthropic", "gemini"
        )
        val MODEL_EXTENSIONS = setOf("tflite", "onnx", "pt", "ptl", "mlmodel", "pb", "gguf", "lin", "keys", "bin")
    }
}
