package com.safesphere.backend.community.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The independence rule (Phase 7, pure; IndependenceTest). Sock-puppet accounts and small coordinated groups have one
 * tell that needs no device id, phone number or location history: they keep backing THE SAME things. Two accounts are
 * LINKED when, among the live (not yet expired) contributions,
 *
 *     shared >= 3   and   shared >= half of the smaller account's own live activity on that side
 *
 * ("side" = supporting a signal, saying it is no longer true, or flagging it). Linked accounts count ONCE: when the
 * supporters of a signal are counted, an account linked to someone already counted adds nothing. This feeds precision
 * (k = 2 must mean two independent people), confidence, retirement and automatic quarantine.
 *
 * Why the "half of their activity" part: active locals legitimately confirm the same popular places; they also do many
 * other things, so their shared part stays a small share. A puppet's activity is almost all shared with its owner.
 * When uncertain the rule is conservative: a linked pair still counts as one real person, never as zero.
 * Uses only rows that exist anyway and are deleted with normal retention, so linkage fades by itself.
 */
public final class Independence {

    /** Shared live signals before two accounts can be linked. */
    public static final int MIN_SHARED = 3;

    private Independence() {
    }

    /** Answers "do these two accounts keep acting together on this side?". */
    @FunctionalInterface
    public interface Linkage {
        boolean linked(long a, long b);

        Linkage NONE = (a, b) -> false;
    }

    public static boolean linked(long shared, long totalA, long totalB) {
        return shared >= MIN_SHARED && shared * 2 >= Math.min(totalA, totalB);
    }

    /**
     * Builds a Linkage from database rows: [pairs] = (userA, userB, shared) and [totals] = live activity per user.
     */
    public static Linkage fromRows(Collection<Object[]> pairs, Map<Long, Long> totals) {
        Set<Pair> linkedPairs = new HashSet<>();
        for (Object[] row : pairs) {
            long a = ((Number) row[0]).longValue();
            long b = ((Number) row[1]).longValue();
            long shared = ((Number) row[2]).longValue();
            if (linked(shared, totals.getOrDefault(a, shared), totals.getOrDefault(b, shared))) {
                linkedPairs.add(Pair.of(a, b));
            }
        }
        if (linkedPairs.isEmpty()) return Linkage.NONE;
        return (a, b) -> a != b && linkedPairs.contains(Pair.of(a, b));
    }

    private record Pair(long low, long high) {
        static Pair of(long a, long b) {
            return new Pair(Math.min(a, b), Math.max(a, b));
        }
    }

    /** Someone taking part, as the counting needs it: who (null = unknown, always independent) and how much weight. */
    public interface Participant {
        Long participant();

        int weight();
    }

    /**
     * Picks the independent participants: strongest weight first (ties: list order), skipping anyone linked to an
     * already picked participant. Greedy on purpose: simple to explain and deterministic.
     */
    public static <P extends Participant> List<P> select(List<P> candidates, Linkage linkage) {
        List<P> ordered = new ArrayList<>(candidates);
        ordered.sort(Comparator.comparingInt((P p) -> -p.weight()));
        List<P> picked = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (P candidate : ordered) {
            Long who = candidate.participant();
            if (who != null) {
                if (!seen.add(who)) continue; // the same person twice can never count twice
                boolean linkedToPicked = false;
                for (P chosen : picked) {
                    if (chosen.participant() != null && linkage.linked(who, chosen.participant())) {
                        linkedToPicked = true;
                        break;
                    }
                }
                if (linkedToPicked) continue;
            }
            picked.add(candidate);
        }
        return picked;
    }
}
