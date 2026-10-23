package com.safesphere.backend.social.policy;

import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * Phase 13A: cleans and checks the free-text parts of a profile (display name, bio, city). Pure.
 *
 * Text is normalised to NFC (Tamil vowel signs compare and count consistently), control and invisible formatting
 * characters (zero-width joiners and the like, often used to dodge filters) are removed, runs of white space become one
 * space, and the ends are trimmed. Links are refused in the name and the bio: a profile is not an advertising space,
 * and links are the usual first step of spam and scams. Lengths count characters (code points), as MySQL does.
 */
public final class ProfileTextPolicy {

    public static final int DISPLAY_NAME_MAX = 40;
    public static final int BIO_MAX = 160;
    public static final int CITY_MAX = 60;

    private static final Pattern LINK = Pattern.compile(
            "(?i)(https?://|www\\.|\\b[a-z0-9-]+\\.(com|in|net|org|io|me|app|co|xyz|link|ly|info|biz|site|online|top)\\b)");
    /** Letters (any script, with their combining marks), spaces, dot, apostrophe and hyphen. */
    private static final Pattern CITY = Pattern.compile("^[\\p{L}\\p{M}][\\p{L}\\p{M} .'-]*$");

    public enum Result { OK, EMPTY, TOO_LONG, CONTAINS_LINK, INVALID_CHARACTERS }

    private ProfileTextPolicy() {
    }

    /** NFC, invisible/control characters removed, white space collapsed, trimmed. Null stays null. */
    public static String clean(String raw) {
        if (raw == null) return null;
        String text = Normalizer.normalize(raw, Normalizer.Form.NFC);
        StringBuilder out = new StringBuilder(text.length());
        text.codePoints().forEach(cp -> {
            int type = Character.getType(cp);
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                out.append(' ');
            } else if (type != Character.CONTROL && type != Character.FORMAT && type != Character.UNASSIGNED
                    && type != Character.PRIVATE_USE && type != Character.SURROGATE) {
                out.appendCodePoint(cp);
            }
        });
        return out.toString().replaceAll(" {2,}", " ").trim();
    }

    public static Result checkDisplayName(String cleaned) {
        if (cleaned == null || cleaned.isEmpty()) return Result.EMPTY;
        if (length(cleaned) > DISPLAY_NAME_MAX) return Result.TOO_LONG;
        if (LINK.matcher(cleaned).find()) return Result.CONTAINS_LINK;
        return Result.OK;
    }

    /** An empty bio is allowed (stored as none). */
    public static Result checkBio(String cleaned) {
        if (cleaned == null || cleaned.isEmpty()) return Result.OK;
        if (length(cleaned) > BIO_MAX) return Result.TOO_LONG;
        if (LINK.matcher(cleaned).find()) return Result.CONTAINS_LINK;
        return Result.OK;
    }

    /** An empty city is allowed (stored as none). */
    public static Result checkCity(String cleaned) {
        if (cleaned == null || cleaned.isEmpty()) return Result.OK;
        if (length(cleaned) > CITY_MAX) return Result.TOO_LONG;
        if (!CITY.matcher(cleaned).matches()) return Result.INVALID_CHARACTERS;
        return Result.OK;
    }

    private static int length(String text) {
        return text.codePointCount(0, text.length());
    }
}
