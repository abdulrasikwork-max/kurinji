package com.safesphere.app.feature.evidence.domain

import java.time.Instant

/**
 * What this phone remembers of one emergency's chain of custody: the newest entry it has seen ([sequenceNo] and
 * [entryHash]) and when it first checked ([since]). No content, no location: a number and a hash.
 */
data class CustodyAnchor(
    val userId: Long,
    val emergencyId: Long,
    val sequenceNo: Long,
    val entryHash: String,
    val since: Instant
)

/** The phone's own judgement of the server's custody record, next to the server's "chain intact". */
sealed interface AnchorStatus {
    /** First check on this phone: nothing to compare with yet (the current state is remembered now). */
    data object FirstCheck : AnchorStatus

    /** Everything this phone saw since [since] is still there, unchanged. */
    data class Matches(val since: Instant) : AnchorStatus

    /**
     * The server's history no longer contains the entry this phone saw: entries up to it were rewritten or removed,
     * even if the server's own check says "intact" (a COMPLETE rewrite of the chain is invisible to the server).
     */
    data class Changed(val since: Instant) : AnchorStatus
}

/**
 * External anchoring of the hash-chained custody ledger, done by the phone (Phase 5 hardening).
 *
 * The server can prove that no single entry was edited, but someone with full database access could rebuild the WHOLE
 * chain with new hashes. Each entry hash covers the previous one, so if the entry this phone saw earlier (same
 * sequence number, same hash) is still in the chain, every entry before it is unchanged too. The phone keeps that one
 * pair per emergency, outside the server's reach. Pure: unit-tested without Android.
 */
object CustodyAnchoring {

    data class Outcome(val status: AnchorStatus, val nextAnchor: CustodyAnchor?)

    /**
     * Compares [report] with the [previous] anchor. [nextAnchor] is what to remember now: advanced to the newest entry
     * when everything matches; unchanged after a mismatch (the evidence of the old state is never overwritten); none
     * when the server's own check failed or the report has no entries.
     */
    fun check(previous: CustodyAnchor?, report: CustodyReport, userId: Long, emergencyId: Long, now: Instant): Outcome {
        val newest = report.entries.filter { it.entryHash != null }.maxByOrNull { it.sequenceNo }
        fun advanced(since: Instant) =
            if (report.chainIntact && newest != null) {
                CustodyAnchor(userId, emergencyId, newest.sequenceNo, newest.entryHash!!, since)
            } else previous

        if (previous == null) {
            return Outcome(AnchorStatus.FirstCheck, advanced(now))
        }
        val seen = report.entries.firstOrNull { it.sequenceNo == previous.sequenceNo }
        return if (seen != null && seen.entryHash == previous.entryHash) {
            Outcome(AnchorStatus.Matches(previous.since), advanced(previous.since))
        } else {
            Outcome(AnchorStatus.Changed(previous.since), previous)
        }
    }
}
