package com.safesphere.backend.social.policy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Phase 17G-1 (decision G-1, V29): the Explore tags ("facets") an experience may carry and an Explore request may ask
 * for, from the ONE file {@code explore/explore-facets-v1.tsv} (bundled byte-identical in the app; V29's CHECK list is the
 * same codes; ExploreFacetsParityTest compares all three). A closed list: nothing free-form ever becomes a tag. Pure.
 */
public final class ExploreFacets {

    public static final String RESOURCE = "/explore/explore-facets-v1.tsv";

    /** Most tags one experience may carry, and most an Explore request may ask for. */
    public static final int MAX_PER_POST = 4;
    public static final int MAX_PER_QUERY = 4;

    /** One tag: its code and its English label, Tamil name and Tanglish name (the last two feed the phone's word list). */
    public record Facet(String code, String english, String tamil, String tanglish) {
    }

    /** The outcome of checking a list of tag codes from a request: the clean codes, or why the list is refused. */
    public record Checked(List<String> codes, String problem) {
        public boolean ok() {
            return problem == null;
        }
    }

    private static final Map<String, Facet> BY_CODE = load();

    private ExploreFacets() {
    }

    /** Every tag, in the file's order. */
    public static List<Facet> all() {
        return List.copyOf(BY_CODE.values());
    }

    public static Set<String> codes() {
        return BY_CODE.keySet();
    }

    public static boolean isValid(String code) {
        return code != null && BY_CODE.containsKey(code);
    }

    /**
     * Checks [raw] (as sent by a client): null = none; every entry must be a listed code (upper-cased, trimmed), none twice,
     * at most [max]. Never throws: the caller turns a problem into a 400 with this plain message.
     */
    public static Checked check(List<String> raw, int max) {
        if (raw == null || raw.isEmpty()) return new Checked(List.of(), null);
        Set<String> seen = new LinkedHashSet<>();
        for (String entry : raw) {
            String code = entry == null ? "" : entry.trim().toUpperCase(Locale.ROOT);
            if (!isValid(code)) return new Checked(List.of(), "Choose tags from the list");
            if (!seen.add(code)) return new Checked(List.of(), "Choose each tag only once");
        }
        if (seen.size() > max) return new Checked(List.of(), "Choose at most " + max + " tags");
        return new Checked(List.copyOf(seen), null);
    }

    private static Map<String, Facet> load() {
        try (InputStream in = ExploreFacets.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("Missing resource " + RESOURCE);
            Map<String, Facet> map = new LinkedHashMap<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank() || line.startsWith("#")) continue;
                    String[] parts = line.split("\t", -1);
                    if (parts.length != 4) throw new IllegalStateException("Bad facet line: " + line);
                    Facet facet = new Facet(parts[0], parts[1], parts[2], parts[3]);
                    if (facet.code().length() > 20 || !facet.code().equals(facet.code().toUpperCase(Locale.ROOT))) {
                        throw new IllegalStateException("Bad facet code: " + facet.code());
                    }
                    if (map.put(facet.code(), facet) != null) throw new IllegalStateException("Duplicate: " + facet.code());
                }
            }
            return java.util.Collections.unmodifiableMap(map);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** For tests: the codes as a list, in file order. */
    static List<String> orderedCodes() {
        return new ArrayList<>(BY_CODE.keySet());
    }
}
