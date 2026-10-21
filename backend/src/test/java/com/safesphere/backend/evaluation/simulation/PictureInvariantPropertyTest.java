package com.safesphere.backend.evaluation.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.safesphere.backend.community.entity.SignalCategory;
import com.safesphere.backend.community.entity.SignalCategory.SignalGroup;
import com.safesphere.backend.community.service.GeoCell;
import com.safesphere.backend.community.service.Independence.Linkage;
import com.safesphere.backend.community.service.SignalPolicy.Confidence;
import com.safesphere.backend.community.service.SignalPolicy.Freshness;
import com.safesphere.backend.evaluation.simulation.AttackReport.Row;
import com.safesphere.backend.intelligence.service.AreaIntelligencePolicy;
import com.safesphere.backend.intelligence.service.AreaIntelligencePolicy.CellPatterns;
import com.safesphere.backend.intelligence.service.AreaIntelligencePolicy.CellPicture;
import com.safesphere.backend.intelligence.service.AreaIntelligencePolicy.Concern;
import com.safesphere.backend.intelligence.service.AreaIntelligencePolicy.Evidence;
import com.safesphere.backend.intelligence.service.AreaIntelligencePolicy.Information;
import com.safesphere.backend.intelligence.service.AreaIntelligencePolicy.Model;
import com.safesphere.backend.intelligence.service.AreaIntelligencePolicy.Outlook;
import com.safesphere.backend.intelligence.service.AreaIntelligencePolicy.Picture;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * Phase 9A property tests of the area rules (docs/PHASE9_DESIGN.md §13, invariants I1-I8): thousands of random
 * evidence sets (fixed seed) through the real AreaIntelligencePolicy. These are the checks any future rule set must
 * also pass. Since 9D they run on the active rules-1.1, and a second test adds I9-I11 for rules-1.1 and rules-2. (The
 * design planned a runtime guard for I1-I5; 9D kept them as property tests instead: both rule sets are deterministic
 * code reviewed with these tests, so a guard would only re-check what the tests prove, docs/PHASE9.md §20.)
 */
class PictureInvariantPropertyTest {

    static final String SECTION = "picture-invariants";
    static final int SETS = 5_000;
    private static final String GEOHASH = "0123456789bcdefghjkmnpqrstuvwxyz";
    private static final SignalCategory[] HAVENS = {SignalCategory.WELL_LIT_BUSY, SignalCategory.OPEN_LATE_STAFFED,
            SignalCategory.HELP_POINT};

    @AfterAll
    static void writeReport() throws IOException {
        AttackReport.write(SECTION, "Phase 9A/9D: area rule invariants on random evidence", AttackReport.PURE_BANNER);
    }

    @Test
    void rulesOne_keepsEveryInvariant_onRandomEvidence() {
        Random random = new Random(9_013L);
        String centre = GeoCell.encode(AttackHarness.ORIGIN_LAT, AttackHarness.ORIGIN_LNG, GeoCell.AREA_PRECISION);
        List<String> cells = List.copyOf(new TreeSet<>(GeoCell.neighbourhood(centre)));
        int checked = 0;
        for (int set = 0; set < SETS; set++) {
            List<Evidence> evidence = randomEvidence(random, cells, random.nextInt(12));
            Map<String, CellPatterns> patterns = new HashMap<>();
            for (String cell : cells) {
                if (random.nextDouble() < 0.15) patterns.put(cell, new CellPatterns(random.nextBoolean(), random.nextBoolean()));
            }
            List<CellPicture> pictures = AreaIntelligencePolicy.cells(cells, evidence, patterns);
            for (CellPicture p : pictures) {
                List<Evidence> in = evidence.stream().filter(e -> e.areaCell().equals(p.cell())).toList();
                CellPatterns pattern = patterns.getOrDefault(p.cell(), CellPatterns.NONE);
                // I1: a concern level always rests on a CONFIRMED concern signal in that cell.
                if (p.concern() != Concern.NONE_CONFIRMED) {
                    assertTrue(in.stream().anyMatch(e -> e.concern() && e.confirmed()), "I1 " + p);
                }
                // I2: the outlook is exactly the published mapping of (concern, information).
                assertEquals(outlook(p.concern(), p.information()), p.outlook(), "I2");
                // I3: "no information" means nothing at all.
                if (p.information() == Information.NONE) {
                    assertTrue(in.isEmpty() && !pattern.anyRecurring(), "I3");
                }
                // I4: concern categories are exactly the confirmed concern categories present.
                Set<SignalCategory> expected = EnumSet.noneOf(SignalCategory.class);
                in.stream().filter(e -> e.concern() && e.confirmed()).forEach(e -> expected.add(e.category()));
                assertEquals(expected, p.concernCategories().isEmpty() ? EnumSet.noneOf(SignalCategory.class)
                        : EnumSet.copyOf(p.concernCategories()), "I4");
                checked++;
            }
            // I5: the neighbourhood is the maximum of its cells, never a sum; a highest cell exactly when concerned.
            Picture neighbourhood = AreaIntelligencePolicy.neighbourhood(centre, cells, evidence, patterns);
            Concern max = pictures.stream().map(CellPicture::concern).max(Concern::compareTo).orElseThrow();
            assertEquals(max, neighbourhood.concern(), "I5 max");
            assertEquals(max != Concern.NONE_CONFIRMED, neighbourhood.highestConcernCell() != null, "I5 highest");
            // I6: adding safe places (confirmed or not) never changes any concern level.
            List<Evidence> withHavens = new ArrayList<>(evidence);
            int havens = 1 + random.nextInt(6);
            for (int i = 0; i < havens; i++) {
                withHavens.add(new Evidence(HAVENS[random.nextInt(HAVENS.length)], randomBlock(random, cells),
                        Confidence.values()[random.nextInt(3)], Freshness.values()[random.nextInt(3)], false));
            }
            assertEquals(concerns(pictures), concerns(AreaIntelligencePolicy.cells(cells, withHavens, patterns)), "I6");
            // I7: removing every unconfirmed (single-person) signal never changes any concern level.
            List<Evidence> confirmedOnly = evidence.stream().filter(Evidence::confirmed).toList();
            assertEquals(concerns(pictures), concerns(AreaIntelligencePolicy.cells(cells, confirmedOnly, patterns)), "I7");
            // I8: a pattern alone (no live signal) never raises a level.
            AreaIntelligencePolicy.cells(cells, List.of(), patterns)
                    .forEach(p -> assertEquals(Concern.NONE_CONFIRMED, p.concern(), "I8"));
        }
        AttackReport.add(SECTION, new Row("rules-1.1 invariants I1-I8", "Do the active rules ever break their own "
                + "promises?", "-", "-", SETS + " random evidence sets, " + checked + " cell pictures checked: 0 violations",
                "One person never raises a concern; safe places never cancel one; a pattern alone raises nothing"));
        AttackReport.note(SECTION, "Fixed seed. I1 concern rests on confirmed evidence; I2 outlook mapping; I3 no "
                + "information = nothing; I4 categories exact; I5 neighbourhood = maximum; I6 havens change no level; I7 "
                + "unconfirmed signals change no level; I8 a pattern alone raises nothing.");
    }

    /**
     * Phase 9D: the same random worlds, now with the people behind each signal and random "acts together" links,
     * through BOTH rule sets: I9 safe places alone never make an area read NO_CONFIRMED_CONCERNS (rules-1.1);
     * I10 rules-2 is never more cautious than rules-1.1 and changes nothing but the concern level; I11 rules-2's
     * extra care from several confirmed concerns always rests on 3 or more distinct people; and I1-I5 for rules-2.
     */
    @Test
    void rulesOnePointOne_andRulesTwo_keepTheNineDInvariants_onRandomEvidence() {
        Random random = new Random(9_042L);
        String centre = GeoCell.encode(AttackHarness.ORIGIN_LAT, AttackHarness.ORIGIN_LNG, GeoCell.AREA_PRECISION);
        List<String> cells = List.copyOf(new TreeSet<>(GeoCell.neighbourhood(centre)));
        int checked = 0;
        int differed = 0;
        for (int set = 0; set < SETS; set++) {
            List<Evidence> evidence = withPeople(random, randomEvidence(random, cells, random.nextInt(12)));
            Linkage links = randomLinkage(random);
            Map<String, CellPatterns> patterns = new HashMap<>();
            for (String cell : cells) {
                if (random.nextDouble() < 0.15) patterns.put(cell, new CellPatterns(random.nextBoolean(), random.nextBoolean()));
            }
            List<CellPicture> active = AreaIntelligencePolicy.cells(Model.RULES_1_1, cells, evidence, patterns, links);
            List<CellPicture> candidate = AreaIntelligencePolicy.cells(Model.RULES_2, cells, evidence, patterns, links);
            for (int i = 0; i < cells.size(); i++) {
                CellPicture one = active.get(i);
                CellPicture two = candidate.get(i);
                String cell = cells.get(i);
                List<Evidence> in = evidence.stream().filter(e -> e.areaCell().equals(cell)).toList();
                assertTrue(one.outlook() != Outlook.NO_CONFIRMED_CONCERNS, "I9 " + one);
                assertTrue(two.concern().compareTo(one.concern()) <= 0, "I10 never more cautious");
                assertEquals(one.information(), two.information(), "I10 information");
                assertEquals(one.reasons(), two.reasons(), "I10 reasons");
                assertEquals(one.concernCategories(), two.concernCategories(), "I10 categories");
                assertEquals(outlook(two.concern(), two.information()), two.outlook(), "I2 for rules-2");
                if (two.concern() != Concern.NONE_CONFIRMED) {
                    assertTrue(in.stream().anyMatch(e -> e.concern() && e.confirmed()), "I1 for rules-2");
                }
                if (two.concern() == Concern.ELEVATED && one.concern() == Concern.ELEVATED && !elevatedOtherwise(in,
                        patterns.getOrDefault(cell, CellPatterns.NONE))) {
                    Set<Long> people = new TreeSet<>();
                    in.stream().filter(e -> e.concern() && e.confirmed() && !aging(e)).forEach(e -> people.addAll(e.people()));
                    assertTrue(people.size() >= 3, "I11 extra care from several concerns rests on >= 3 people");
                }
                if (two.concern() != one.concern()) differed++;
                checked++;
            }
            Picture neighbourhood = AreaIntelligencePolicy.neighbourhood(Model.RULES_2, centre, cells, evidence, patterns,
                    links);
            Concern max = candidate.stream().map(CellPicture::concern).max(Concern::compareTo).orElseThrow();
            assertEquals(max, neighbourhood.concern(), "I5 for rules-2");
        }
        AttackReport.add(SECTION, new Row("9D invariants I9-I11 (rules-1.1 and rules-2)", "Do the 9D rules keep "
                + "their promises?", "-", "-", SETS + " random evidence sets with random people and links, " + checked
                + " cell pictures under both rule sets: 0 violations; rules-2 read differently in " + differed,
                "Safe places alone never read \"no confirmed concerns\"; rules-2 is never more cautious and changes "
                        + "only the concern level"));
    }

    /** Random supporters (1-4 of 8 people) on each signal: what SignalPolicy would have counted as independent. */
    private static List<Evidence> withPeople(Random random, List<Evidence> evidence) {
        List<Evidence> result = new ArrayList<>();
        for (Evidence e : evidence) {
            Set<Long> people = new TreeSet<>();
            int n = 1 + random.nextInt(4);
            while (people.size() < n) people.add((long) random.nextInt(8));
            result.add(new Evidence(e.category(), e.cell(), e.confidence(), e.freshness(), e.disputed(), Set.copyOf(people)));
        }
        return result;
    }

    private static Linkage randomLinkage(Random random) {
        Set<Long> linked = new HashSet<>();
        for (long a = 0; a < 8; a++) {
            for (long b = a + 1; b < 8; b++) if (random.nextDouble() < 0.15) linked.add(a * 8 + b);
        }
        return (a, b) -> linked.contains(Math.min(a, b) * 8 + Math.max(a, b));
    }

    /** Rule (a) or (c) of rules-1 holds, so ELEVATED does not rest on rule (b). */
    private static boolean elevatedOtherwise(List<Evidence> in, CellPatterns pattern) {
        boolean strongIncidentNow = in.stream().anyMatch(e -> e.concern() && e.group() == SignalGroup.INCIDENT
                && e.confidence() == Confidence.HIGH && e.freshness() == Freshness.TODAY);
        boolean recurring = pattern.incidentsRecurring() && in.stream()
                .anyMatch(e -> e.concern() && e.confirmed() && e.group() == SignalGroup.INCIDENT);
        return strongIncidentNow || recurring;
    }

    private static boolean aging(Evidence e) {
        return e.group() != SignalGroup.INCIDENT && e.freshness() == Freshness.EARLIER;
    }

    private static List<Evidence> randomEvidence(Random random, List<String> cells, int count) {
        List<Evidence> list = new ArrayList<>();
        SignalCategory[] all = SignalCategory.values();
        for (int i = 0; i < count; i++) {
            list.add(new Evidence(all[random.nextInt(all.length)], randomBlock(random, cells),
                    Confidence.values()[random.nextInt(3)], Freshness.values()[random.nextInt(3)],
                    random.nextDouble() < 0.2));
        }
        return list;
    }

    private static String randomBlock(Random random, List<String> cells) {
        // Few blocks per cell, so same-block contradictions actually occur.
        return cells.get(random.nextInt(cells.size())) + GEOHASH.charAt(random.nextInt(4));
    }

    private static List<Concern> concerns(List<CellPicture> pictures) {
        return pictures.stream().map(CellPicture::concern).toList();
    }

    /** The published mapping (docs/PHASE8_DESIGN.md §5.6), written out independently of the code under test. */
    private static Outlook outlook(Concern concern, Information information) {
        if (concern == Concern.ELEVATED) return Outlook.TAKE_EXTRA_CARE;
        if (concern == Concern.SOME) return Outlook.TAKE_CARE;
        return switch (information) {
            case NONE -> Outlook.NO_INFORMATION;
            case LITTLE -> Outlook.LITTLE_INFORMATION;
            case SOME, MORE -> Outlook.NO_CONFIRMED_CONCERNS;
        };
    }
}
