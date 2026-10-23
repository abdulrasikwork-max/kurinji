package com.safesphere.backend.social.policy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Phase 13A: the closed lists a profile may name (V20 CHECKs use the same codes). Stored comma-separated in a fixed
 * order without duplicates, so the same choice always gives the same text.
 */
public final class ProfileLists {

    /** Languages a person can write in (launch community: Tamil Nadu, D5). Stored as lower-case codes. */
    public enum Language { TA, EN, HI, ML, TE, KN }

    /** The experience categories of the design (EXPERIENCE_PLATFORM_DESIGN.md §3.6). */
    public enum Interest {
        FOOD_CAFES, NATURE_OUTDOORS, CULTURE_HERITAGE, MUSIC_NIGHTLIFE, STUDY_WORK, DAY_TRIPS, ACTIVITIES_SPORT,
        HOBBIES_MAKING, CITY_LIFE, HIDDEN_GEMS
    }

    private ProfileLists() {
    }

    /** Null or empty input = none (null). Throws IllegalArgumentException on an unknown code. */
    public static String encodeLanguages(List<String> codes) {
        if (codes == null || codes.isEmpty()) return null;
        Set<Language> set = EnumSet.noneOf(Language.class);
        for (String code : codes) set.add(Language.valueOf(code.trim().toUpperCase(Locale.ROOT)));
        List<String> out = new ArrayList<>();
        for (Language language : set) out.add(language.name().toLowerCase(Locale.ROOT));
        return String.join(",", out);
    }

    public static String encodeInterests(List<String> codes) {
        if (codes == null || codes.isEmpty()) return null;
        Set<Interest> set = EnumSet.noneOf(Interest.class);
        for (String code : codes) set.add(Interest.valueOf(code.trim().toUpperCase(Locale.ROOT)));
        List<String> out = new ArrayList<>();
        for (Interest interest : set) out.add(interest.name());
        return String.join(",", out);
    }

    /** The stored text as the API returns it: lower-case language codes / upper-case interest codes; empty if none. */
    public static List<String> decode(String stored) {
        if (stored == null || stored.isEmpty()) return List.of();
        return Arrays.asList(stored.split(","));
    }
}
