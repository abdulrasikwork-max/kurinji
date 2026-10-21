package com.safesphere.backend.community.service;

import java.time.Duration;

/**
 * Contributor tiers (Phase 7, pure; TrustPolicyTest). A tier is PRIVATE: it is never shown to anyone (not even the
 * person), never ranked and never compared. It only decides how much a person's contributions weigh, in half-points:
 *
 *   RESTRICTED  0  paused by a moderator, or account disabled: contributions do not count at all
 *   LIMITED     1  has a moderation strike, OR at least 3 reports nobody independent ever backed (refuted > corroborated)
 *   STANDARD    2  everyone else (a new account counts only after its 24 h probation, see CommunityService)
 *   TRUSTED     3  account >= 30 days old, >= 3 reports independently corroborated, corroborated >= 3 x refuted,
 *                  no strike
 *
 * Why so few levels and such small differences: trust here is a tie-breaker that makes manipulation slower and
 * honest track records count a little more; it must never let one account outweigh independent people. A TRUSTED
 * person's single report is still only shown to ~1 km and still "not yet confirmed" (k = 2 is about PEOPLE).
 * All counters halve every 90 days, so both good and bad history fade.
 *
 * Flags weigh the same as the tier, halved (rounded down) for someone whose flags moderators keep rejecting.
 */
public final class TrustPolicy {

    public enum Tier {
        RESTRICTED(0), LIMITED(1), STANDARD(2), TRUSTED(3);

        private final int halfPoints;

        Tier(int halfPoints) {
            this.halfPoints = halfPoints;
        }

        public int weight() {
            return halfPoints;
        }
    }

    /** The counters a tier is computed from (from contributor_standing; all zero for someone new). */
    public record Standing(int corroborated, int refuted, int strikes, int flagsUpheld, int flagsRejected,
                           boolean restricted) {
        public static final Standing NEW = new Standing(0, 0, 0, 0, 0, false);
    }

    static final Duration TRUSTED_MIN_AGE = Duration.ofDays(30);
    static final int TRUSTED_MIN_CORROBORATED = 3;
    static final int LIMITED_MIN_REFUTED = 3;
    static final int UNRELIABLE_FLAGGER_MIN_REJECTED = 3;

    private TrustPolicy() {
    }

    public static Tier tier(Standing standing, Duration accountAge, boolean enabled) {
        if (!enabled || standing.restricted()) return Tier.RESTRICTED;
        if (standing.strikes() > 0) return Tier.LIMITED;
        if (standing.refuted() >= LIMITED_MIN_REFUTED && standing.refuted() > standing.corroborated()) return Tier.LIMITED;
        if (accountAge.compareTo(TRUSTED_MIN_AGE) >= 0
                && standing.corroborated() >= TRUSTED_MIN_CORROBORATED
                && standing.corroborated() >= 3 * standing.refuted()) {
            return Tier.TRUSTED;
        }
        return Tier.STANDARD;
    }

    /** Weight of this person's FLAGS (half-points). */
    public static int flagWeight(Tier tier, Standing standing) {
        int weight = tier.weight();
        if (standing.flagsRejected() >= UNRELIABLE_FLAGGER_MIN_REJECTED
                && standing.flagsRejected() > 2 * standing.flagsUpheld()) {
            weight /= 2;
        }
        return weight;
    }
}
