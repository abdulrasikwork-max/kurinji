package com.safesphere.backend.community;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.safesphere.backend.community.service.GeoCell;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GeoCellTest {

    @Test
    void encodesTheSharedVectors_sameAsThePhone() throws Exception {
        List<String> lines;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                getClass().getResourceAsStream("/geohash-vectors.csv"), StandardCharsets.UTF_8))) {
            lines = reader.lines().filter(l -> !l.isBlank() && !l.startsWith("#")).toList();
        }
        assertTrue(lines.size() >= 10);
        for (String line : lines) {
            String[] parts = line.split(",");
            String expected = parts[2];
            assertEquals(expected, GeoCell.encode(Double.parseDouble(parts[0]), Double.parseDouble(parts[1]), 7), line);
            // Every prefix of a cell is the bigger cell that contains it.
            assertEquals(expected.substring(0, 6),
                    GeoCell.encode(Double.parseDouble(parts[0]), Double.parseDouble(parts[1]), 6), line);
        }
    }

    @Test
    void centreLiesInsideTheCellAndReEncodesToIt() {
        for (String cell : List.of("tdr1v9q", "u4pruyd", "7zzzzzz", "tdr1v9")) {
            double[] c = GeoCell.centre(cell);
            assertEquals(cell, GeoCell.encode(c[0], c[1], cell.length()));
        }
    }

    @Test
    void blockCellIsAboutOneHundredFiftyMetres_areaCellAboutOneKilometre() {
        double[] block = GeoCell.bounds("tdr1v9q");
        double blockHeight = GeoCell.distanceMeters(block[0], block[2], block[1], block[2]);
        assertTrue(blockHeight > 130 && blockHeight < 160, "block height " + blockHeight);
        double[] area = GeoCell.bounds("tdr1v9");
        double areaWidth = GeoCell.distanceMeters(area[0], area[2], area[0], area[3]);
        assertTrue(areaWidth > 1000 && areaWidth < 1300, "area width " + areaWidth);
    }

    @Test
    void neighbourhoodIsTheThreeByThreeBlock() {
        Set<String> cells = GeoCell.neighbourhood("tdr1y5");
        assertEquals(Set.of("tdr1vf", "tdr1y4", "tdr1y6", "tdr1vg", "tdr1y5", "tdr1y7", "tdr1vu", "tdr1yh", "tdr1yk"), cells);
    }

    @Test
    void neighbourhoodWrapsAcrossTheAntimeridian() {
        Set<String> cells = GeoCell.neighbourhood("xbpbpb");
        assertEquals(9, cells.size());
        assertTrue(cells.contains("800000"), "east of 180 degrees continues at -180");
    }

    @Test
    void validationAcceptsOnlyLowerCaseBase32OfTheRightLength() {
        assertTrue(GeoCell.isValid("tdr1v9q", 7));
        assertFalse(GeoCell.isValid("tdr1v9", 7));
        assertFalse(GeoCell.isValid("TDR1V9Q", 7));
        assertFalse(GeoCell.isValid("tdr1vaq", 7)); // 'a' is not in the geohash alphabet
        assertFalse(GeoCell.isValid("tdr1viq", 7)); // nor 'i', 'l', 'o'
        assertFalse(GeoCell.isValid(null, 7));
        assertThrows(IllegalArgumentException.class, () -> GeoCell.encode(91, 0, 7));
    }

    @Test
    void adjacency_tellsSidesFromCorners_andMatchesTheNeighbourhood() {
        // tdr1y5 sits in the middle of the 3 x 3 block above: north tdr1yh, south tdr1y4, west tdr1vg, east tdr1y7.
        assertEquals(GeoCell.Adjacency.SAME, GeoCell.adjacency("tdr1y5", "tdr1y5"));
        for (String side : List.of("tdr1yh", "tdr1y4", "tdr1vg", "tdr1y7")) {
            assertEquals(GeoCell.Adjacency.EDGE, GeoCell.adjacency("tdr1y5", side), side);
            assertEquals(GeoCell.Adjacency.EDGE, GeoCell.adjacency(side, "tdr1y5"), "symmetric " + side);
        }
        for (String corner : List.of("tdr1vu", "tdr1yk", "tdr1vf", "tdr1y6")) {
            assertEquals(GeoCell.Adjacency.CORNER, GeoCell.adjacency("tdr1y5", corner), corner);
        }
        assertEquals(GeoCell.Adjacency.NONE, GeoCell.adjacency("tdr1y5", "tdr1ye"), "two cells away");
        assertEquals(GeoCell.Adjacency.NONE, GeoCell.adjacency("tdr1y5", "tdr1y5q"), "different sizes never touch");
        assertTrue(GeoCell.adjacency("xbpbpb", "800000") != GeoCell.Adjacency.NONE, "the antimeridian is not a wall");
        // Every block in a neighbourhood is SAME, EDGE or CORNER, and exactly four are EDGE (away from the poles).
        Random random = new Random(9_041L);
        for (int i = 0; i < 2_000; i++) {
            String cell = GeoCell.encode(random.nextDouble() * 160 - 80, random.nextDouble() * 360 - 180, 7);
            long sides = GeoCell.neighbourhood(cell).stream()
                    .map(c -> GeoCell.adjacency(cell, c)).filter(a -> a == GeoCell.Adjacency.EDGE).count();
            assertEquals(4, sides, cell);
            assertTrue(GeoCell.neighbourhood(cell).stream().noneMatch(c -> GeoCell.adjacency(cell, c) == GeoCell.Adjacency.NONE));
        }
    }

    @Test
    void aRegionIsExactlyItsThirtyTwoAreaCells() {
        List<String> children = GeoCell.children("tdr1v");
        assertEquals(32, children.size());
        assertEquals(32, Set.copyOf(children).size());
        assertTrue(children.contains("tdr1v9"));
        for (String child : children) {
            assertTrue(GeoCell.isValid(child, GeoCell.AREA_PRECISION));
            double[] c = GeoCell.centre(child);
            assertEquals("tdr1v", GeoCell.encode(c[0], c[1], GeoCell.REGION_PRECISION));
        }
    }
}
