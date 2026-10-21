package com.safesphere.backend.community.service;

import com.safesphere.backend.community.entity.SignalCategory;
import com.safesphere.backend.community.entity.SignalCategory.SignalGroup;
import com.safesphere.backend.community.service.Independence.Linkage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * coord-1 (Phase 9C, docs/PHASE9_DESIGN.md §7.5.1; measured in docs/PHASE9.md §3): finds groups of accounts whose
 * support looks coordinated, so that a HUMAN can look. Pure and deterministic (CoordinationPolicyTest). It decides
 * nothing about content or people: ModerationService/CoordinationCheck only ever open a review case from it.
 *
 * Phase 7's independence rule links two accounts only when they share >= 3 live signals AND that is half of their
 * activity. The 9A simulation showed two cheap ways around it, and these rules target exactly those:
 *
 *   R1 SUPPORT_CORE   rotating rings (every pair backs at most 2 signals). Take the signals with at most 3 backers
 *                     (popular places are ignored, so padding with them changes nothing); connect two people when they
 *                     back one of them together; keep the 2-CORE (repeatedly drop anyone with fewer than two partners:
 *                     one active person whose reports strangers confirm disappears, a ring does not). A connected group
 *                     of >= 3 people backing >= 4 confirmed signals that nobody outside it backs is a finding.
 *   R2 EXCLUSIVE_PAIR padded puppets: two people, NOT linked by Phase 7, who are the only backers of >= 3 signals.
 *
 * Both need the signals to spread beyond ONE neighbourhood (3 x 3 area cells, ~3.6 x 1.8 km): a hostel, one street or
 * neighbours walking their own area are never findings (local damage stays local, and rules-2 is the answer there).
 * Thresholds are the ones the simulation measured against; they raise an attacker's cost, they do not make evasion
 * impossible (bigger rings with 4+ backers per signal escape R1 and tend to get linked by Phase 7 instead).
 */
public final class CoordinationPolicy {

    /** Recorded in logs and docs so measurements can be tied to the rules that produced them. */
    public static final String VERSION = "coord-1";

    public enum Rule { SUPPORT_CORE, EXCLUSIVE_PAIR }

    /**
     * One visible signal as the rules need it: [supporters] = the accounts that currently COUNT behind it (opaque keys,
     * after delay, probation, lifetime and weight), [confirmed] = MODERATE or HIGH confidence.
     */
    public record Backing(long signalId, SignalCategory category, String areaCell, boolean confirmed,
                          Set<Long> supporters) {
    }

    /** One finding: the people (opaque keys, never returned) and the signals only they back. */
    public record Group(Rule rule, Set<Long> people, List<Backing> signals) {

        public int areaCells() {
            return (int) signals.stream().map(Backing::areaCell).distinct().count();
        }

        public boolean contains(long signalId) {
            return signals.stream().anyMatch(b -> b.signalId() == signalId);
        }

        /**
         * The people this finding is ABOUT: those who back at least two of the group's places. The 2-core can also take
         * in people through ONE place: a pair who back one place together, which a ring member also confirmed, then
         * both have two partners. They must not be listed to a moderator as members, paused with the group, or let a
         * ring shelter behind their place, so they are left out here (detection itself is unchanged). Every member of
         * a ring or an exclusive pair backs several of its places.
         */
        public Set<Long> members() {
            Map<Long, Integer> places = new HashMap<>();
            signals.forEach(b -> b.supporters().forEach(p -> places.merge(p, 1, Integer::sum)));
            Set<Long> result = new TreeSet<>();
            places.forEach((p, n) -> {
                if (n >= 2 && people.contains(p)) result.add(p);
            });
            return Collections.unmodifiableSet(result);
        }

        /**
         * The signal a case is opened on: the one doing the most harm. That is the signal in the area where this
         * group backs the most signals (two confirmed concerns in one area make "take extra care"), then concerns
         * before safe places, incidents before conditions, then the oldest. Deterministic.
         */
        public long target() {
            Map<String, Long> perArea = new HashMap<>();
            signals.forEach(b -> perArea.merge(b.areaCell(), 1L, Long::sum));
            return signals.stream().min(Comparator
                    .comparingLong((Backing b) -> -perArea.get(b.areaCell()))
                    .thenComparingInt(b -> b.category().group() == SignalGroup.HAVEN ? 1 : 0)
                    .thenComparingInt(b -> b.category().group() == SignalGroup.INCIDENT ? 0 : 1)
                    .thenComparingLong(Backing::signalId)).orElseThrow().signalId();
        }
    }

    static final int LOW_SUPPORT_MAX = 3;
    static final int CORE_MIN_PEOPLE = 3;
    static final int CORE_MIN_CONFIRMED = 4;
    static final int PAIR_MIN_EXCLUSIVE = 3;

    private CoordinationPolicy() {
    }

    /** All findings, R1 first. [supportLinkage] is Phase 7's (a linked pair is R0's job, not R2's). */
    public static List<Group> groups(Collection<Backing> backings, Linkage supportLinkage) {
        List<Group> all = new ArrayList<>(supportCores(backings));
        all.addAll(exclusivePairs(backings, supportLinkage));
        return all;
    }

    static List<Group> supportCores(Collection<Backing> backings) {
        List<Backing> low = lowSupport(backings);
        Map<Long, Set<Long>> partners = new HashMap<>();
        for (Backing b : low) {
            for (Long x : b.supporters()) {
                for (Long y : b.supporters()) {
                    if (!x.equals(y)) partners.computeIfAbsent(x, k -> new HashSet<>()).add(y);
                }
            }
        }
        Set<Long> core = new TreeSet<>(partners.keySet());
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Long p : List.copyOf(core)) {
                if (partners.get(p).stream().filter(core::contains).count() < 2) {
                    core.remove(p);
                    changed = true;
                }
            }
        }
        List<Group> groups = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (Long first : core) {
            if (!seen.add(first)) continue;
            Set<Long> component = new TreeSet<>(List.of(first));
            Deque<Long> queue = new ArrayDeque<>(List.of(first));
            while (!queue.isEmpty()) {
                for (Long next : partners.get(queue.poll())) {
                    if (core.contains(next) && seen.add(next)) {
                        component.add(next);
                        queue.add(next);
                    }
                }
            }
            if (component.size() < CORE_MIN_PEOPLE) continue;
            List<Backing> confirmed = low.stream()
                    .filter(b -> b.confirmed() && component.containsAll(b.supporters())).toList();
            if (confirmed.size() >= CORE_MIN_CONFIRMED && !withinOneNeighbourhood(areas(confirmed))) {
                groups.add(new Group(Rule.SUPPORT_CORE, Set.copyOf(component), confirmed));
            }
        }
        return groups;
    }

    static List<Group> exclusivePairs(Collection<Backing> backings, Linkage supportLinkage) {
        Map<List<Long>, List<Backing>> byPair = new LinkedHashMap<>();
        for (Backing b : lowSupport(backings)) {
            if (b.supporters().size() != 2) continue;
            byPair.computeIfAbsent(List.copyOf(new TreeSet<>(b.supporters())), k -> new ArrayList<>()).add(b);
        }
        List<Group> groups = new ArrayList<>();
        byPair.forEach((pair, list) -> {
            if (list.size() >= PAIR_MIN_EXCLUSIVE && !supportLinkage.linked(pair.get(0), pair.get(1))
                    && !withinOneNeighbourhood(areas(list))) {
                groups.add(new Group(Rule.EXCLUSIVE_PAIR, Set.copyOf(pair), List.copyOf(list)));
            }
        });
        return groups;
    }

    /** True when some 3 x 3 block of area cells contains all of them. */
    public static boolean withinOneNeighbourhood(Collection<String> areaCells) {
        if (areaCells.isEmpty()) return true;
        Set<String> centres = new HashSet<>();
        areaCells.forEach(c -> centres.addAll(GeoCell.neighbourhood(c)));
        return centres.stream().anyMatch(centre -> GeoCell.neighbourhood(centre).containsAll(areaCells));
    }

    private static List<Backing> lowSupport(Collection<Backing> backings) {
        return backings.stream()
                .filter(b -> b.supporters().size() >= 2 && b.supporters().size() <= LOW_SUPPORT_MAX)
                .sorted(Comparator.comparingLong(Backing::signalId))
                .toList();
    }

    private static Set<String> areas(List<Backing> backings) {
        Set<String> areas = new TreeSet<>();
        backings.forEach(b -> areas.add(b.areaCell()));
        return areas;
    }
}
