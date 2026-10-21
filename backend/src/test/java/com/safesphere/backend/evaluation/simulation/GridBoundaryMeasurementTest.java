package com.safesphere.backend.evaluation.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.safesphere.backend.community.service.GeoCell;
import com.safesphere.backend.evaluation.simulation.AttackReport.Row;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * Phase 9A, finding F7 (docs/PHASE9_DESIGN.md §1.3): how often do two honest people who report the same street from
 * two random points along it land in the SAME ~150 m block (today's merge rule), and how often in neighbouring blocks
 * (the 9D adjacent-merge candidate)? Pure geometry with the real GeoCell code and a fixed seed, so the numbers are
 * reproducible. The model (uniform points on a straight segment, random direction) is an ASSUMPTION; real reporters
 * cluster where the problem is worst, which would raise both numbers.
 */
class GridBoundaryMeasurementTest {

    static final String SECTION = "grid-boundary";
    static final int TRIALS = 20_000;
    private static final double METERS_PER_DEGREE = 111_320.0;

    @AfterAll
    static void writeReport() throws IOException {
        AttackReport.write(SECTION, "Phase 9A: two honest reports of one street and the ~150 m grid",
                AttackReport.PURE_BANNER);
    }

    @Test
    void twoReportsOfOneStreet_shareABlockFarLessOftenThanANeighbourhoodOfBlocks() {
        for (int length : new int[] {100, 200, 300, 500}) {
            Random random = new Random(20_261_021L + length);
            int same = 0;
            int neighbouring = 0;
            for (int t = 0; t < TRIALS; t++) {
                double lat = AttackHarness.ORIGIN_LAT + random.nextDouble() * 0.2 - 0.1;
                double lng = AttackHarness.ORIGIN_LNG + random.nextDouble() * 0.2 - 0.1;
                double direction = random.nextDouble() * 2 * Math.PI;
                String a = cellAlong(lat, lng, direction, random.nextDouble() * length);
                String b = cellAlong(lat, lng, direction, random.nextDouble() * length);
                if (a.equals(b)) same++;
                if (GeoCell.neighbourhood(a).contains(b)) neighbouring++;
            }
            double pSame = same / (double) TRIALS;
            double pNeighbouring = neighbouring / (double) TRIALS;
            assertTrue(pNeighbouring >= pSame);
            // Pinned from the measured run of 2026-10-21 (fixed seed, so a change means the code or model changed).
            double[] expected = switch (length) {
                case 100 -> new double[] {0.745, 1.000};
                case 200 -> new double[] {0.531, 0.997};
                case 300 -> new double[] {0.399, 0.950};
                default -> new double[] {0.252, 0.734};
            };
            assertEquals(expected[0], pSame, 0.01, "same block, " + length + " m");
            assertEquals(expected[1], pNeighbouring, 0.01, "same or neighbouring block, " + length + " m");
            AttackReport.add(SECTION, new Row("Street of " + length + " m", "Two honest reporters at random points",
                    "none (honest)", "-", String.format(Locale.ROOT,
                    "Same block (merged today): %.1f %% ± %.1f; same or neighbouring block (candidate): %.1f %% ± %.1f",
                    100 * pSame, 100 * halfWidth(pSame), 100 * pNeighbouring, 100 * halfWidth(pNeighbouring)),
                    String.format(Locale.ROOT, "Today %.0f %% of honest pairs stay two \"not yet confirmed\" signals",
                            100 * (1 - pSame))));
        }
        AttackReport.note(SECTION, TRIALS + " trials per length, fixed seed, 95 % normal-approximation intervals. "
                + "Uniform points on a straight segment in a random direction (an assumption).");
    }

    /**
     * Phase 9D: which neighbours should a lone report join? Honest pairs on one street (should join) against two
     * DIFFERENT places a fixed distance apart (joining them would claim one place where there are two).
     */
    @Test
    void sideNeighboursCatchMostHonestPairs_cornerNeighboursAddMostlyDifferentPlaces() {
        // Pinned from the measured run of 2026-10-21 (fixed seeds): {same, same or side, same or any of the 8}.
        Map<Integer, double[]> street = Map.of(100, new double[] {0.742, 0.976, 1.000}, 200, new double[] {0.535, 0.903, 0.997},
                300, new double[] {0.396, 0.786, 0.946}, 500, new double[] {0.257, 0.567, 0.736});
        Map<Integer, double[]> apart = Map.of(150, new double[] {0.048, 0.686, 1.000}, 200, new double[] {0.000, 0.377, 0.806},
                300, new double[] {0.000, 0.020, 0.179}, 400, new double[] {0.000, 0.000, 0.001});
        for (int length : new int[] {100, 200, 300, 500}) {
            double[] p = rates(length, true);
            assertPinned(street.get(length), p, "street of " + length + " m");
            AttackReport.add(SECTION, new Row("9D street of " + length + " m", "Two honest reporters at random points: "
                    + "does a lone report reach the other one?", "none (honest)", "-", String.format(Locale.ROOT,
                    "Same block %.1f %%; same or SIDE neighbour (9D rule) %.1f %%; same or any of 8 neighbours %.1f %%",
                    100 * p[0], 100 * p[1], 100 * p[2]), "Higher is better (honest corroboration kept)"));
        }
        for (int distance : new int[] {150, 200, 300, 400}) {
            double[] p = rates(distance, false);
            assertPinned(apart.get(distance), p, distance + " m apart");
            AttackReport.add(SECTION, new Row("9D two places " + distance + " m apart", "Two DIFFERENT places: how often "
                    + "would they be joined into one?", "none (honest)", "-", String.format(Locale.ROOT,
                    "Same block %.1f %%; same or SIDE neighbour (9D rule) %.1f %%; same or any of 8 neighbours %.1f %%",
                    100 * p[0], 100 * p[1], 100 * p[2]), "Lower is better (different places kept apart)"));
        }
        AttackReport.note(SECTION, "9D chose SIDE neighbours: for a 300 m street they keep 78.6 % of honest pairs (39.6 % "
                + "today), and they join only 2.0 % of places 300 m apart (any of 8 neighbours: 94.6 % and 17.9 %).");
    }

    private static void assertPinned(double[] expected, double[] measured, String what) {
        assertEquals(expected[0], measured[0], 0.01, "same block, " + what);
        assertEquals(expected[1], measured[1], 0.01, "same or side neighbour, " + what);
        assertEquals(expected[2], measured[2], 0.01, "same or any neighbour, " + what);
    }

    /** [0] same block, [1] same or side neighbour, [2] same or any of the 8 neighbours. */
    private static double[] rates(int meters, boolean anywhereAlong) {
        Random random = new Random(20_261_022L + meters + (anywhereAlong ? 0 : 7));
        int same = 0;
        int edge = 0;
        int any = 0;
        for (int t = 0; t < TRIALS; t++) {
            double lat = AttackHarness.ORIGIN_LAT + random.nextDouble() * 0.2 - 0.1;
            double lng = AttackHarness.ORIGIN_LNG + random.nextDouble() * 0.2 - 0.1;
            double direction = random.nextDouble() * 2 * Math.PI;
            String a = cellAlong(lat, lng, direction, anywhereAlong ? random.nextDouble() * meters : 0);
            String b = cellAlong(lat, lng, direction, anywhereAlong ? random.nextDouble() * meters : meters);
            GeoCell.Adjacency adjacency = GeoCell.adjacency(a, b);
            if (adjacency == GeoCell.Adjacency.SAME) same++;
            if (adjacency == GeoCell.Adjacency.SAME || adjacency == GeoCell.Adjacency.EDGE) edge++;
            if (adjacency != GeoCell.Adjacency.NONE) any++;
        }
        return new double[] {same / (double) TRIALS, edge / (double) TRIALS, any / (double) TRIALS};
    }

    private static String cellAlong(double lat, double lng, double direction, double meters) {
        double dLat = meters * Math.cos(direction) / METERS_PER_DEGREE;
        double dLng = meters * Math.sin(direction) / (METERS_PER_DEGREE * Math.cos(Math.toRadians(lat)));
        return GeoCell.encode(lat + dLat, lng + dLng, GeoCell.BLOCK_PRECISION);
    }

    private static double halfWidth(double p) {
        return 1.96 * Math.sqrt(p * (1 - p) / TRIALS);
    }
}
