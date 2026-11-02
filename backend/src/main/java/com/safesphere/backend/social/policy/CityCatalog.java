package com.safesphere.backend.social.policy;

import com.safesphere.backend.community.service.GeoCell;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Phase 17E (D-5, V28): the cities Kurinji knows, from the ONE catalog file {@code cities/cities-v1.tsv} (also bundled
 * byte-identical in the app, and seeded into the {@code cities} table by V28; CityCatalogTest compares the three). Each
 * city is an approximate bounding box around its urban area; the centre of a place's cell decides which city a place is
 * in (never the phone). Boxes never overlap. {@link #OTHER} is a post-only value ("a city not on the list"): no place is
 * ever in OTHER and no Edition is made for it. Replaces 13C's CityAreas and the closed City enum. Pure.
 */
public final class CityCatalog {

    /** The post-only "another city" code. */
    public static final String OTHER = "OTHER";
    public static final String RESOURCE = "/cities/cities-v1.tsv";

    /** One city of the catalog. Box = {latMin, latMax, lngMin, lngMax} in degrees. */
    public record City(String code, String name, String state, double latMin, double latMax, double lngMin,
                       double lngMax, List<String> aliases) {

        boolean holds(double lat, double lng) {
            return lat >= latMin && lat <= latMax && lng >= lngMin && lng <= lngMax;
        }
    }

    private static final Map<String, City> BY_CODE = load();

    private CityCatalog() {
    }

    /** Every city, in the catalog's order. */
    public static List<City> all() {
        return List.copyOf(BY_CODE.values());
    }

    /** The city with [code] (exact, upper-case), or empty. OTHER is not a catalog city. */
    public static Optional<City> find(String code) {
        return code == null ? Optional.empty() : Optional.ofNullable(BY_CODE.get(code));
    }

    /** The city whose box holds the centre of [cell] (any geohash length), or empty. */
    public static Optional<String> cityOf(String cell) {
        double[] centre = GeoCell.centre(cell);
        for (City city : BY_CODE.values()) {
            if (city.holds(centre[0], centre[1])) return Optional.of(city.code());
        }
        return Optional.empty();
    }

    /** A city an Edition or a list can be made for (a catalog city, never OTHER), from a request; empty if unknown. */
    public static Optional<String> listCity(String raw) {
        if (raw == null) return Optional.empty();
        String code = raw.trim().toUpperCase(Locale.ROOT);
        return BY_CODE.containsKey(code) ? Optional.of(code) : Optional.empty();
    }

    /** A city a CITY-precision post may name: a catalog city or OTHER; empty if unknown. */
    public static Optional<String> postCity(String raw) {
        if (raw == null) return Optional.empty();
        String code = raw.trim().toUpperCase(Locale.ROOT);
        return BY_CODE.containsKey(code) || OTHER.equals(code) ? Optional.of(code) : Optional.empty();
    }

    /** The catalog file's city lines, parsed (also used by tests to compare the migration seed). */
    public static List<City> parse(InputStream in) throws IOException {
        List<City> out = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] f = line.split("\t", -1);
                if (f.length != 8) throw new IllegalStateException("A catalog line needs 8 fields: " + line);
                List<String> aliases = f[7].isBlank() ? List.of()
                        : Arrays.stream(f[7].split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
                out.add(new City(f[0], f[1], f[2], Double.parseDouble(f[3]), Double.parseDouble(f[4]),
                        Double.parseDouble(f[5]), Double.parseDouble(f[6]), aliases));
            }
        }
        return out;
    }

    private static Map<String, City> load() {
        try (InputStream in = CityCatalog.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("Missing " + RESOURCE);
            Map<String, City> map = new LinkedHashMap<>();
            for (City city : parse(in)) {
                if (map.put(city.code(), city) != null) throw new IllegalStateException("Duplicate city " + city.code());
            }
            return Collections.unmodifiableMap(map);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
