package com.safesphere.app.core.textcheck

import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Phase 13E rung 2 on the phone (D2: a plain-Kotlin linear model, weights as data, no ML runtime): character n-grams
 * (2-4) of every normalised token plus its phonetic key, hashed with 32-bit FNV-1a into 2^18 buckets, logistic
 * regression per category. The same scores as kurinji_moderation/linear.py for
 * moderation-service/data/vectors/linear-vectors.tsv (LinearModelTest, a toy model about food and travel).
 *
 * NO MODEL SHIPS in 13E (docs/PHASE13E_PLAN.md E1/E2): no dataset under D3 exists yet. A model file would go to
 * assets/textcheck/ (ModelPlacesTest) once it has earned it; until then the nudge uses the lexicon only.
 */
class LinearModel private constructor(
    val version: String,
    private val temperature: Double,
    private val categories: Map<String, Pair<Double, Map<Int, Double>>>
) {

    fun scores(text: String): Map<String, Double> {
        val (buckets, value) = features(text)
        return categories.keys.sorted().associateWith { name ->
            val (bias, weights) = categories.getValue(name)
            var z = bias
            for (b in buckets) weights[b]?.let { z += it * value }
            sigmoid(z / temperature)
        }
    }

    companion object {
        const val DIMS = 1 shl 18
        private const val FNV_OFFSET = 0x811C9DC5L
        private const val FNV_PRIME = 0x01000193L

        fun fromText(text: String): LinearModel {
            val lines = text.lines().filter { it.isNotBlank() }
            val header = lines.first().split('\t')
            require(header[0] == "SSLIN1" && header[2] == TextNormalizer.VERSION && header[3].toInt() == DIMS) {
                "not a lin model for norm-1"
            }
            val categories = LinkedHashMap<String, Pair<Double, MutableMap<Int, Double>>>()
            var current: String? = null
            for (line in lines.drop(1)) {
                val f = line.split('\t')
                when {
                    f[0] == "C" -> {
                        current = f[1]
                        categories[f[1]] = f[2].toDouble() to HashMap()
                    }
                    f[0] == "W" && current != null -> categories.getValue(current).second[f[1].toInt()] = f[2].toDouble()
                    else -> throw IllegalArgumentException("malformed lin model")
                }
            }
            return LinearModel(header[1], header[4].toDouble(), categories)
        }

        fun fnv1a(text: String): Long {
            var h = FNV_OFFSET
            for (byte in text.toByteArray(Charsets.UTF_8)) {
                h = h xor (byte.toLong() and 0xFF)
                h = (h * FNV_PRIME) and 0xFFFFFFFFL
            }
            return h
        }

        /** Sorted bucket indices and the shared value of each (the vector is L2-normalised). */
        fun features(text: String): Pair<List<Int>, Double> {
            val buckets = java.util.TreeSet<Int>()
            for (token in TextNormalizer.normalise(text).tokens) {
                val padded = ("<" + token.text + ">").codePoints().toArray()
                for (n in 2..4) {
                    for (i in 0..padded.size - n) {
                        val gram = TextNormalizer.codePoints(padded.slice(i until i + n))
                        buckets.add((fnv1a("c$n:$gram") % DIMS).toInt())
                    }
                }
                for ((space, key) in PhoneticKey.keys(token)) {
                    if (space == "ta") buckets.add((fnv1a("k:$key") % DIMS).toInt())
                }
            }
            val value = if (buckets.isEmpty()) 0.0 else 1.0 / sqrt(buckets.size.toDouble())
            return buckets.toList() to value
        }

        fun sigmoid(z: Double): Double = if (z >= 0) 1.0 / (1.0 + exp(-z)) else exp(z).let { it / (1.0 + it) }
    }
}
