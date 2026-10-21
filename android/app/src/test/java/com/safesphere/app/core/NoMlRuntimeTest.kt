package com.safesphere.app.core

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 9 rule (docs/PHASE9_DESIGN.md Appendix A, "no black box where a rule is better"; the Android twin of the
 * backend's EvaluationIsolationTest.noMachineLearningRuntime_inTheBackend): the app ships no on-device model and no
 * machine-learning or AI-service library. Everything it shows comes from deterministic rules with reason codes.
 */
class NoMlRuntimeTest {

    @Test
    fun `no machine learning runtime or AI service library in the app`() {
        val files = listOf(File("../gradle/libs.versions.toml"), File("build.gradle.kts"))
        files.forEach { assertTrue("missing $it", it.exists()) }
        val text = files.joinToString("\n") { it.readText(Charsets.UTF_8) }.lowercase()
        for (runtime in FORBIDDEN) {
            assertFalse("no ML runtime or AI service: $runtime", text.contains(runtime))
        }
    }

    @Test
    fun `no model file is bundled`() {
        val models = File("src/main").walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in MODEL_EXTENSIONS }.map { it.name }.toList()
        assertTrue("no on-device model: $models", models.isEmpty())
    }

    private companion object {
        val FORBIDDEN = listOf(
            "tensorflow", "tflite", "litert", "mlkit", "mediapipe", "onnx", "pytorch", "djl", "tribuo", "smile",
            "weka", "genai", "openai", "anthropic", "gemini"
        )
        val MODEL_EXTENSIONS = setOf("tflite", "onnx", "pt", "ptl", "mlmodel", "pb", "gguf")
    }
}
