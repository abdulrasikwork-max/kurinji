package com.safesphere.backend.community.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Geohash cells (Phase 6). A geohash names a rectangle of the Earth with a short base-32 string; every extra character
 * makes it ~32 times smaller. SafeSphere uses two sizes:
 *   precision 7 = BLOCK cell, ~153 m x 153 m at the equator (where a report is placed)
 *   precision 6 = AREA cell,  ~1.2 km x 0.6 km (what others see until a second person confirms; area queries)
 *
 * Pure and dependency-free. The phone has the same encoder (core/location/GeoHash.kt); both are tested against the
 * same vectors, so a cell computed on the phone is the cell the server expects.
 */
public final class GeoCell {

    public static final int BLOCK_PRECISION = 7;
    public static final int AREA_PRECISION = 6;
    /** Phase 8: a region (~4.9 km x 4.9 km), the unit of "Check the way" downloads. */
    public static final int REGION_PRECISION = 5;

    private static final String BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz";
    private static final Pattern VALID = Pattern.compile("^[0-9b-hjkmnp-z]+$");

    private GeoCell() {
    }

    /** True when [cell] is a well-formed lower-case geohash of exactly [precision] characters. */
    public static boolean isValid(String cell, int precision) {
        return cell != null && cell.length() == precision && VALID.matcher(cell).matches();
    }

    public static String encode(double latitude, double longitude, int precision) {
        if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180 || precision < 1 || precision > 12) {
            throw new IllegalArgumentException("Invalid coordinate or precision");
        }
        double latLo = -90, latHi = 90, lngLo = -180, lngHi = 180;
        StringBuilder hash = new StringBuilder(precision);
        boolean evenBit = true; // geohash interleaves bits starting with longitude
        int bit = 0;
        int value = 0;
        while (hash.length() < precision) {
            if (evenBit) {
                double mid = (lngLo + lngHi) / 2;
                if (longitude >= mid) { value = (value << 1) | 1; lngLo = mid; } else { value <<= 1; lngHi = mid; }
            } else {
                double mid = (latLo + latHi) / 2;
                if (latitude >= mid) { value = (value << 1) | 1; latLo = mid; } else { value <<= 1; latHi = mid; }
            }
            evenBit = !evenBit;
            if (++bit == 5) {
                hash.append(BASE32.charAt(value));
                bit = 0;
                value = 0;
            }
        }
        return hash.toString();
    }

    /** The rectangle a cell covers: {latMin, latMax, lngMin, lngMax}. */
    public static double[] bounds(String cell) {
        double latLo = -90, latHi = 90, lngLo = -180, lngHi = 180;
        boolean evenBit = true;
        for (int i = 0; i < cell.length(); i++) {
            int value = BASE32.indexOf(cell.charAt(i));
            if (value < 0) throw new IllegalArgumentException("Invalid geohash");
            for (int b = 4; b >= 0; b--) {
                boolean one = ((value >> b) & 1) == 1;
                if (evenBit) {
                    double mid = (lngLo + lngHi) / 2;
                    if (one) lngLo = mid; else lngHi = mid;
                } else {
                    double mid = (latLo + latHi) / 2;
                    if (one) latLo = mid; else latHi = mid;
                }
                evenBit = !evenBit;
            }
        }
        return new double[] {latLo, latHi, lngLo, lngHi};
    }

    /** Centre of the cell, rounded to 5 decimals (~1 m): more digits would only suggest false precision. */
    public static double[] centre(String cell) {
        double[] b = bounds(cell);
        return new double[] {round5((b[0] + b[1]) / 2), round5((b[2] + b[3]) / 2)};
    }

    /** The cell itself and its (up to) 8 neighbours of the same size: a 3 x 3 block. */
    public static Set<String> neighbourhood(String cell) {
        double[] b = bounds(cell);
        double height = b[1] - b[0];
        double width = b[3] - b[2];
        double lat = (b[0] + b[1]) / 2;
        double lng = (b[2] + b[3]) / 2;
        Set<String> cells = new LinkedHashSet<>();
        for (int dLat = -1; dLat <= 1; dLat++) {
            double nLat = lat + dLat * height;
            if (nLat < -90 || nLat > 90) continue; // no cells beyond the poles
            for (int dLng = -1; dLng <= 1; dLng++) {
                double nLng = lng + dLng * width;
                if (nLng > 180) nLng -= 360;   // wrap across the antimeridian
                if (nLng < -180) nLng += 360;
                cells.add(encode(nLat, nLng, cell.length()));
            }
        }
        return cells;
    }

    /** How two cells of the same size touch (Phase 9D): the same cell, sharing a side, sharing only a corner, or not. */
    public enum Adjacency { SAME, EDGE, CORNER, NONE }

    /**
     * Phase 9D: whether [a] and [b] (same precision) are the same cell, side neighbours, corner neighbours or further
     * apart, from their positions in the grid (with the antimeridian wrap, like [neighbourhood]).
     */
    public static Adjacency adjacency(String a, String b) {
        if (a.length() != b.length()) return Adjacency.NONE;
        if (a.equals(b)) return Adjacency.SAME;
        double[] ba = bounds(a);
        double[] bb = bounds(b);
        double height = ba[1] - ba[0];
        double width = ba[3] - ba[2];
        long rows = Math.round(((bb[0] + bb[1]) / 2 - (ba[0] + ba[1]) / 2) / height);
        double dLng = (bb[2] + bb[3]) / 2 - (ba[2] + ba[3]) / 2;
        if (dLng > 180) dLng -= 360;
        if (dLng < -180) dLng += 360;
        long columns = Math.round(dLng / width);
        if (Math.abs(rows) > 1 || Math.abs(columns) > 1) return Adjacency.NONE;
        return rows == 0 || columns == 0 ? Adjacency.EDGE : Adjacency.CORNER;
    }

    /** Phase 8: the 32 cells one character finer that make up [cell] (a 5-character region has 32 area cells). */
    public static List<String> children(String cell) {
        List<String> children = new ArrayList<>(BASE32.length());
        for (char c : BASE32.toCharArray()) children.add(cell + c);
        return children;
    }

    /** Approximate distance in metres between two points (equirectangular; fine for a few kilometres). */
    public static double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        double x = Math.toRadians(lng2 - lng1) * Math.cos(Math.toRadians((lat1 + lat2) / 2));
        double y = Math.toRadians(lat2 - lat1);
        return Math.sqrt(x * x + y * y) * 6_371_000;
    }

    private static double round5(double value) {
        return Math.round(value * 100_000d) / 100_000d;
    }
}
