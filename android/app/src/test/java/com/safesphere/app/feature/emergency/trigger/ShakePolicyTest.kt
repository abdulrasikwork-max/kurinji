package com.safesphere.app.feature.emergency.trigger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 17F (D-4): three strong shakes within two seconds - and nothing else - is a shake. */
class ShakePolicyTest {

    private val policy = ShakePolicy()
    private val gravity = 9.81f

    /** Feeds samples and returns the times at which the policy said "shake". */
    private fun run(samples: List<Triple<Long, Float, Triple<Float, Float, Float>>>): List<Long> {
        var state = ShakePolicy.State()
        val triggers = mutableListOf<Long>()
        for ((at, _, xyz) in samples) {
            val result = policy.onSample(state, at, xyz.first, xyz.second, xyz.third)
            state = result.state
            if (result.triggered) triggers += at
        }
        return triggers
    }

    private fun rest(from: Long, to: Long, step: Long = 20) =
        (from until to step step).map { Triple(it, 0f, Triple(0f, 0f, gravity)) }

    /** A back-and-forth stroke along x: +[strength], -[strength], ... starting at [at], [gap] ms apart. */
    private fun shake(at: Long, strokes: Int, strength: Float = 45f, gap: Long = 100) =
        (0 until strokes).map { i ->
            Triple(at + i * gap, strength, Triple(if (i % 2 == 0) strength else -strength, 0f, gravity))
        }

    @Test
    fun `a phone lying still never triggers`() {
        assertTrue(run(rest(0, 20_000)).isEmpty())
    }

    @Test
    fun `three firm back-and-forth shakes inside two seconds trigger once`() {
        val samples = rest(0, 500) + shake(500, strokes = 3)
        assertEquals(listOf(700L), run(samples))
    }

    @Test
    fun `two shakes are not enough`() {
        assertTrue(run(rest(0, 500) + shake(500, strokes = 2)).isEmpty())
    }

    @Test
    fun `weak shaking does not count`() {
        assertTrue(run(rest(0, 500) + shake(500, strokes = 6, strength = 8f)).isEmpty())
    }

    @Test
    fun `strokes spread over more than two seconds do not trigger`() {
        val samples = rest(0, 500) + shake(500, strokes = 3, gap = 1_200)
        assertTrue(run(samples).isEmpty())
    }

    @Test
    fun `strong hits that all push the same way (running, a drop, a knock) do not trigger`() {
        val knocks = (0 until 8).map { i ->
            val at = 500L + i * 300
            Triple(at, 30f, Triple(0f, 0f, gravity + 30f))
        }
        val samples = rest(0, 500) + knocks.flatMap { knock ->
            // rest again between impacts, as in a stride
            listOf(knock) + rest(knock.first + 20, knock.first + 280, 20)
        }
        assertTrue(run(samples).isEmpty())
    }

    @Test
    fun `a steady jogging stride that alternates but never jerks hard does not trigger (found on the A23)`() {
        // Real trace: jogging with the phone in a pocket gave 17-29 m/s2 peaks every ~300 ms for several seconds.
        val stride = shake(500, strokes = 24, strength = 26f, gap = 300)
        val samples = rest(0, 500) + stride
        assertTrue(run(samples).isEmpty())
    }

    @Test
    fun `a hard run - impacts every 300 ms with some jerks as strong as a light shake - does not trigger (found on the A23)`() {
        // Real trace: after the first strides of a run, jerks of 36-58 m/s2 appeared inside a train of 18-27 m/s2 impacts.
        val train = (0 until 20).map { i ->
            val strength = if (i == 10) 45f else 24f
            Triple(500L + i * 300, strength, Triple(if (i % 2 == 0) strength else -strength, 0f, gravity))
        }
        assertTrue(run(rest(0, 500) + train).isEmpty())
    }

    @Test
    fun `the same burst that triggers from rest does not trigger right after another strong peak`() {
        val fromRest = rest(0, 500) + shake(500, strokes = 3)
        assertEquals(1, run(fromRest).size)
        // One impact 400 ms before the burst (a stride, a step off a kerb): not a burst that started from rest.
        val afterImpact = rest(0, 100) + shake(100, strokes = 1, strength = 26f, gap = 100) + rest(200, 500) +
            shake(500, strokes = 3)
        assertTrue(run(afterImpact).isEmpty())
        // ... but the same burst after more than 800 ms of quiet is a shake again.
        val afterQuiet = rest(0, 100) + shake(100, strokes = 1, strength = 26f, gap = 100) + rest(200, 1_500) +
            shake(1_500, strokes = 3)
        assertEquals(1, run(afterQuiet).size)
    }

    @Test
    fun `a shake that starts with a hard jerk and then weakens still triggers`() {
        // Real trace shape: the first jerk 35+, the follow-ups only 18-25.
        val samples = rest(0, 500) + shake(500, strokes = 1, strength = 45f) +
            shake(600, strokes = 3, strength = 22f).mapIndexed { i, s ->
                // keep the alternation: the hard jerk was +x, so the next strokes start with -x
                Triple(s.first, s.second, Triple(if (i % 2 == 0) -22f else 22f, 0f, gravity))
            }
        assertEquals(1, run(samples).size)
    }

    @Test
    fun `a hard jerk only counts together with the other peaks inside the two seconds`() {
        // One 45 jerk, then jogging-strength strokes arriving more than two seconds later: still not a shake.
        val samples = rest(0, 500) + shake(500, strokes = 1, strength = 45f) + rest(600, 3_000) +
            shake(3_000, strokes = 6, strength = 26f, gap = 300)
        assertTrue(run(samples).isEmpty())
    }

    @Test
    fun `peaks closer than the minimum gap are one movement`() {
        // Three strong samples 30 ms apart: sensor ringing of one jolt, not three shakes.
        val ringing = listOf(
            Triple(500L, 30f, Triple(30f, 0f, gravity)),
            Triple(530L, 30f, Triple(-30f, 0f, gravity)),
            Triple(560L, 30f, Triple(30f, 0f, gravity))
        )
        assertTrue(run(rest(0, 500) + ringing).isEmpty())
    }

    @Test
    fun `after a trigger the same gesture cannot fire again for ten seconds`() {
        val first = rest(0, 500) + shake(500, strokes = 3)
        val tooSoon = rest(700, 2_000) + shake(2_000, strokes = 3)
        assertEquals(1, run(first + tooSoon).size)
        val later = rest(700, 11_000) + shake(11_000, strokes = 3)
        assertEquals(2, run(first + later).size)
    }

    @Test
    fun `a late batch from a sleeping sensor is dropped, a fresh sample is kept`() {
        assertTrue(ShakePolicy.isFresh(sampleMillis = 10_000, nowMillis = 10_200))
        assertTrue(ShakePolicy.isFresh(10_000, 11_500))
        assertFalse("a shake from 20 s ago must not open a countdown now", ShakePolicy.isFresh(10_000, 30_000))
        assertFalse(ShakePolicy.isFresh(10_000, 11_501))
        // a clock a little ahead of the sample is tolerated, one far ahead is not
        assertTrue(ShakePolicy.isFresh(10_000, 9_500))
        assertFalse(ShakePolicy.isFresh(10_000, 5_000))
    }

    @Test
    fun `the first sample only seeds gravity so a phone picked up mid-air does not trigger`() {
        val state = ShakePolicy.State()
        val result = policy.onSample(state, 0, 40f, 40f, 40f)
        assertFalse(result.triggered)
        assertTrue(result.state.primed)
    }
}
