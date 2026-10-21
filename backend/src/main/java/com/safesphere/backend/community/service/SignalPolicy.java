package com.safesphere.backend.community.service;

import com.safesphere.backend.community.entity.ContributionKind;
import com.safesphere.backend.community.entity.SignalCategory;
import com.safesphere.backend.community.service.Independence.Linkage;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * The rules that turn a signal's contributions into what others may see (Phase 6, extended in Phase 7). Pure, so every
 * rule is a unit test (SignalPolicyTest). Only contributions that COUNT are used: countsFrom has passed (publication
 * delay and new-account probation are over), it is within the category's lifetime, and its weight is above 0
 * (a restricted or disabled account counts for nothing).
 *
 * Phase 7 adds two ideas (docs/PHASE7.md §3):
 *   INDEPENDENT people: supporters linked to an already counted supporter (Independence) do not count again.
 *   WEIGHTS in half-points from the private tier (TrustPolicy): LIMITED 1, STANDARD 2, TRUSTED 3.
 *
 *   I  = independent supporters (REPORT + CONFIRM), W  = their weight
 *   ID = independent disputers (NO_LONGER),          WD = their weight
 *   retired      = ID >= 2 and WD >= W          (two independent people needed; one group cannot retire a signal)
 *   visible      = W >= 2 (one standard person) and not retired; a LIMITED person's lone report waits for support
 *   precision    = BLOCK (~150 m) only when I >= 2, else AREA (~1 km): a block is never revealed by one person or
 *                  by one person's puppets (k = 2 independent people)
 *   support      = SINGLE (1) | SEVERAL (2-4) | MANY (5+) independent people, never an exact count
 *   confidence   = HIGH when I >= 3 and W - WD >= 7; MODERATE when I >= 2 and W - WD >= 4; otherwise LOW
 *                  (with all-standard people: 2 people = MODERATE, 4 = HIGH; disputes pull it down)
 *   freshness    = from the newest counted support: TODAY (< 24 h) | THIS_WEEK (< 7 d) | EARLIER
 *   independentFreshness (Phase 8) = the same bands, but from INDEPENDENT support only, each linked group represented
 *                  by its OLDEST member: accounts that keep backing each other cannot keep a signal "today"
 *                  (docs/PHASE8_DESIGN.md §7.2). The radar keeps [freshness]; Safety Intelligence uses this one.
 *   disputed     = ID >= 1 (shown as "some say this has changed")
 *   expired      = nothing counts or will count any more: the row is deleted
 * Moderation (quarantine / removal) is applied on top of this by the service, never inside it.
 */
public final class SignalPolicy {

    public enum Precision { AREA, BLOCK }

    public enum Support { SINGLE, SEVERAL, MANY }

    public enum Freshness { TODAY, THIS_WEEK, EARLIER }

    /** How sure the system is (Phase 7). Shown to people with plain-language reasons; never "verified". */
    public enum Confidence { LOW, MODERATE, HIGH }

    /** Why a signal has its confidence (Phase 7). Aggregate facts only; nothing about any one person. */
    public enum Reason {
        /** Only one independent person stands behind it so far. */
        SINGLE_REPORT,
        /** Independent people nearby said the same. */
        CONFIRMED_BY_OTHERS,
        /** Some of the people behind it have a good track record. */
        ESTABLISHED_CONTRIBUTORS,
        /** Some support came from accounts that keep acting together; they were counted once. */
        LINKED_SUPPORT_COUNTED_ONCE,
        /** Someone said it has changed. */
        DISPUTED
    }

    /**
     * One contribution as the policy needs it: [weight] in half-points (2 = a standard person), [participant] an
     * opaque key used only to apply the independence rule (null = unknown, always independent). No id leaves here.
     */
    public record Vote(ContributionKind kind, LocalDateTime countsFrom, int weight, Long participant)
            implements Independence.Participant {

        public Vote(ContributionKind kind, LocalDateTime countsFrom) {
            this(kind, countsFrom, STANDARD_WEIGHT, null);
        }
    }

    /**
     * [supporters] / [disputers] are INDEPENDENT people; [weightedSupport] / [weightedDispute] in half-points.
     * [independentFreshness] (Phase 8): freshness from independent support only (see the class comment).
     * [independentSupporters] (Phase 9D): the opaque participant keys of those independent supporters, for rules that
     * count people ACROSS signals (rules-2). In memory only: an Assessment is never serialised.
     */
    public record Assessment(boolean visible, boolean retired, boolean expired, int supporters, int disputers,
                             Precision precision, Support support, Freshness freshness, boolean disputed,
                             Confidence confidence, List<Reason> reasons, int weightedSupport, int weightedDispute,
                             Freshness independentFreshness, List<Long> independentSupporters) {
    }

    /** One supporter's moment, for the independent-freshness selection (weights deliberately equal). */
    private record Seen(Long participant, LocalDateTime at) implements Independence.Participant {
        @Override
        public int weight() {
            return 0;
        }
    }

    static final int STANDARD_WEIGHT = 2;
    static final int BLOCK_PRECISION_SUPPORTERS = 2;
    static final int RETIRE_MIN_DISPUTERS = 2;
    static final int VISIBLE_MIN_WEIGHT = 2;
    static final int MODERATE_MIN_NET = 4;
    static final int HIGH_MIN_NET = 7;
    static final int HIGH_MIN_SUPPORTERS = 3;
    static final int ESTABLISHED_WEIGHT = 3;

    private SignalPolicy() {
    }

    public static Assessment assess(SignalCategory category, List<Vote> votes, LocalDateTime now) {
        return assess(category, votes, now, Linkage.NONE, Linkage.NONE);
    }

    /**
     * @param supportLinkage who keeps supporting the same signals together
     * @param disputeLinkage who keeps saying "no longer true" to the same signals together
     */
    public static Assessment assess(SignalCategory category, List<Vote> votes, LocalDateTime now,
                                    Linkage supportLinkage, Linkage disputeLinkage) {
        LocalDateTime oldestAlive = now.minus(category.lifetime());
        List<Vote> countedSupport = new ArrayList<>();
        List<Vote> countedDispute = new ArrayList<>();
        boolean anyAlive = false;
        for (Vote vote : votes) {
            if (!vote.countsFrom().isAfter(oldestAlive)) continue; // too old: no longer counts
            anyAlive = true;
            if (vote.countsFrom().isAfter(now)) continue;          // not counting yet (delay / probation)
            if (vote.weight() <= 0) continue;                      // restricted / disabled account
            (vote.kind().supports() ? countedSupport : countedDispute).add(vote);
        }
        List<Vote> support = Independence.select(countedSupport, supportLinkage);
        List<Vote> dispute = Independence.select(countedDispute, disputeLinkage);
        int supporters = support.size();
        int disputers = dispute.size();
        int weightedSupport = support.stream().mapToInt(Vote::weight).sum();
        int weightedDispute = dispute.stream().mapToInt(Vote::weight).sum();

        boolean retired = disputers >= RETIRE_MIN_DISPUTERS && weightedDispute >= weightedSupport;
        boolean visible = weightedSupport >= VISIBLE_MIN_WEIGHT && !retired;
        Precision precision = supporters >= BLOCK_PRECISION_SUPPORTERS ? Precision.BLOCK : Precision.AREA;
        Support band = supporters >= 5 ? Support.MANY : supporters >= 2 ? Support.SEVERAL : Support.SINGLE;
        int net = weightedSupport - weightedDispute;
        Confidence confidence = supporters >= HIGH_MIN_SUPPORTERS && net >= HIGH_MIN_NET ? Confidence.HIGH
                : supporters >= BLOCK_PRECISION_SUPPORTERS && net >= MODERATE_MIN_NET ? Confidence.MODERATE
                : Confidence.LOW;

        List<Reason> reasons = new ArrayList<>();
        reasons.add(supporters >= 2 ? Reason.CONFIRMED_BY_OTHERS : Reason.SINGLE_REPORT);
        if (supporters >= 2 && support.stream().anyMatch(v -> v.weight() >= ESTABLISHED_WEIGHT)) {
            reasons.add(Reason.ESTABLISHED_CONTRIBUTORS);
        }
        if (countedSupport.size() > supporters) reasons.add(Reason.LINKED_SUPPORT_COUNTED_ONCE);
        if (disputers >= 1) reasons.add(Reason.DISPUTED);

        LocalDateTime newestSupport = countedSupport.stream().map(Vote::countsFrom)
                .max(LocalDateTime::compareTo).orElse(null);
        return new Assessment(visible, retired, !anyAlive, supporters, disputers, precision, band,
                freshness(newestSupport, now), disputers >= 1, confidence, List.copyOf(reasons),
                weightedSupport, weightedDispute,
                freshness(newestIndependentSupport(countedSupport, supportLinkage), now),
                support.stream().map(Vote::participant).filter(Objects::nonNull).toList());
    }

    /**
     * Oldest first with equal weights, so the greedy independence selection keeps each linked group's FIRST member and
     * skips the later ones: a group can make a signal no fresher than the first of them made it.
     */
    private static LocalDateTime newestIndependentSupport(List<Vote> countedSupport, Linkage linkage) {
        List<Seen> byAge = countedSupport.stream()
                .sorted(Comparator.comparing(Vote::countsFrom))
                .map(v -> new Seen(v.participant(), v.countsFrom()))
                .toList();
        return Independence.select(byAge, linkage).stream().map(Seen::at)
                .max(LocalDateTime::compareTo).orElse(null);
    }

    private static Freshness freshness(LocalDateTime newestSupport, LocalDateTime now) {
        if (newestSupport == null) return Freshness.EARLIER;
        Duration age = Duration.between(newestSupport, now);
        if (age.compareTo(Duration.ofHours(24)) < 0) return Freshness.TODAY;
        if (age.compareTo(Duration.ofDays(7)) < 0) return Freshness.THIS_WEEK;
        return Freshness.EARLIER;
    }
}
