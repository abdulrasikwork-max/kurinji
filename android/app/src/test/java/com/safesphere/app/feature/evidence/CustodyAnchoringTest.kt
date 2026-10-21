package com.safesphere.app.feature.evidence

import com.safesphere.app.feature.evidence.domain.AnchorStatus
import com.safesphere.app.feature.evidence.domain.CustodyAnchor
import com.safesphere.app.feature.evidence.domain.CustodyAnchoring
import com.safesphere.app.feature.evidence.domain.CustodyEntry
import com.safesphere.app.feature.evidence.domain.CustodyReport
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Phase 5 hardening: the phone's own anchor on the server's custody chain (pure). */
class CustodyAnchoringTest {

    private val t0 = Instant.parse("2026-10-20T09:00:00Z")
    private val later = Instant.parse("2026-10-21T09:00:00Z")

    private fun entry(sequence: Long, hash: String) = CustodyEntry(sequence, 1, "SEALED", t0, hash)
    private fun report(vararg entries: CustodyEntry, intact: Boolean = true) =
        CustodyReport(intact, entries.size.toLong(), entries.lastOrNull()?.entryHash, t0, entries.toList())

    @Test
    fun `the first check remembers the newest entry`() {
        val outcome = CustodyAnchoring.check(null, report(entry(1, "h1"), entry(2, "h2")), USER, EMERGENCY, t0)

        assertEquals(AnchorStatus.FirstCheck, outcome.status)
        assertEquals(CustodyAnchor(USER, EMERGENCY, 2, "h2", t0), outcome.nextAnchor)
    }

    @Test
    fun `an unchanged history matches and the anchor moves forward, keeping its first date`() {
        val anchor = CustodyAnchor(USER, EMERGENCY, 2, "h2", t0)

        val outcome = CustodyAnchoring.check(anchor, report(entry(1, "h1"), entry(2, "h2"), entry(5, "h5")), USER, EMERGENCY, later)

        assertEquals(AnchorStatus.Matches(t0), outcome.status)
        assertEquals(CustodyAnchor(USER, EMERGENCY, 5, "h5", t0), outcome.nextAnchor)
    }

    @Test
    fun `a rewritten chain is noticed even when the server calls it intact, and the old anchor is kept`() {
        val anchor = CustodyAnchor(USER, EMERGENCY, 2, "h2", t0)

        val rewritten = CustodyAnchoring.check(anchor, report(entry(1, "x1"), entry(2, "x2"), entry(3, "x3")), USER, EMERGENCY, later)
        assertEquals(AnchorStatus.Changed(t0), rewritten.status)
        assertEquals(anchor, rewritten.nextAnchor)

        val truncated = CustodyAnchoring.check(anchor, report(entry(1, "h1")), USER, EMERGENCY, later)
        assertEquals(AnchorStatus.Changed(t0), truncated.status)
        assertEquals(anchor, truncated.nextAnchor)
    }

    @Test
    fun `a chain the server itself reports broken is never used as a new anchor`() {
        val first = CustodyAnchoring.check(null, report(entry(1, "h1"), intact = false), USER, EMERGENCY, t0)
        assertEquals(AnchorStatus.FirstCheck, first.status)
        assertNull(first.nextAnchor)

        val anchor = CustodyAnchor(USER, EMERGENCY, 1, "h1", t0)
        val stillThere = CustodyAnchoring.check(anchor, report(entry(1, "h1"), entry(2, "h2"), intact = false), USER, EMERGENCY, later)
        assertEquals(AnchorStatus.Matches(t0), stillThere.status)
        assertEquals(anchor, stillThere.nextAnchor)
    }

    @Test
    fun `no entries yet means nothing to remember`() {
        val outcome = CustodyAnchoring.check(null, report(), USER, EMERGENCY, t0)
        assertEquals(AnchorStatus.FirstCheck, outcome.status)
        assertNull(outcome.nextAnchor)
    }

    private companion object {
        const val USER = 7L
        const val EMERGENCY = 42L
    }
}
