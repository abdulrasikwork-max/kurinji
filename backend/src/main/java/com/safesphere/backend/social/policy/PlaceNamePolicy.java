package com.safesphere.backend.social.policy;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Phase 13B: cleans and checks the name of a new place. Pure.
 *
 * Cleaning is the profile cleaning (NFC, invisible characters removed, spaces collapsed). A name has 2-60 characters,
 * at least one letter or digit, and no links (a place is not an advert). Names that obviously describe somebody's
 * private place ("my home", "hostel room", "en veedu", an address with a door number) are refused: a public place there
 * would tell everyone where a person lives (design §3.5; the phone also offers City or None for private places).
 *
 * The normalised name (lower-case, letters and digits only, single spaces) is what makes "Marina Beach",
 * "marina  beach" and "Marina-Beach" one place in a cell.
 */
public final class PlaceNamePolicy {

    public static final int MIN = 2;
    public static final int MAX = 60;

    public enum Result { OK, TOO_SHORT, TOO_LONG, CONTAINS_LINK, NO_LETTERS, PRIVATE_PLACE }

    private static final Pattern LINK = Pattern.compile(
            "(?i)(https?://|www\\.|\\b[a-z0-9-]+\\.(com|in|net|org|io|me|app|co|xyz|link|ly|info|biz|site|online|top)\\b)");

    /** Whole normalised names that only ever mean someone's own place. */
    private static final List<String> PRIVATE_EXACT = List.of("home", "house", "my place", "room", "hostel room",
            "pg", "flat", "apartment", "veedu", "en veedu", "enga veedu", "my veedu", "வீடு", "என் வீடு", "எங்க வீடு");

    /** "my ..." / "our ..." + a private kind of place. */
    private static final Pattern PRIVATE_OWNED = Pattern.compile(
            "^(my|our|mine|en|enga|engal) (home|house|room|flat|apartment|hostel|hostel room|pg|place|office|"
                    + "workplace|work|college|school|veedu|street|building)( .*)?$");

    /** Address-like names: "door no 12", "flat no 4b", "room 204". */
    private static final Pattern ADDRESS = Pattern.compile(
            "\\b(door|flat|house|room) ?(no|number)? ?[0-9]+[a-z]?\\b");

    private PlaceNamePolicy() {
    }

    /** The profile cleaning: NFC, invisible characters removed, white space collapsed, trimmed. */
    public static String clean(String raw) {
        return ProfileTextPolicy.clean(raw);
    }

    public static Result check(String cleaned) {
        if (cleaned == null || length(cleaned) < MIN) return Result.TOO_SHORT;
        if (length(cleaned) > MAX) return Result.TOO_LONG;
        if (LINK.matcher(cleaned).find()) return Result.CONTAINS_LINK;
        String normalized = normalize(cleaned);
        if (normalized.isEmpty()) return Result.NO_LETTERS;
        if (PRIVATE_EXACT.contains(normalized) || PRIVATE_OWNED.matcher(normalized).matches()
                || ADDRESS.matcher(normalized).find()) {
            return Result.PRIVATE_PLACE;
        }
        return Result.OK;
    }

    /** Lower-case, letters (with their marks) and digits only, single spaces. Used for de-duplication. */
    public static String normalize(String cleaned) {
        if (cleaned == null) return "";
        StringBuilder out = new StringBuilder(cleaned.length());
        cleaned.toLowerCase(Locale.ROOT).codePoints().forEach(cp -> {
            if (Character.isLetterOrDigit(cp) || Character.getType(cp) == Character.NON_SPACING_MARK
                    || Character.getType(cp) == Character.COMBINING_SPACING_MARK) {
                out.appendCodePoint(cp);
            } else {
                out.append(' ');
            }
        });
        String result = out.toString().replaceAll(" {2,}", " ").trim();
        return result.codePointCount(0, result.length()) > MAX ? result.substring(0, result.offsetByCodePoints(0, MAX)).trim()
                : result;
    }

    private static int length(String text) {
        return text.codePointCount(0, text.length());
    }
}
