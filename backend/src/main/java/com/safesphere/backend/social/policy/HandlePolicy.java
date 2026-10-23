package com.safesphere.backend.social.policy;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Phase 13A: handle rules (the same as the V20 CHECK). Lower-case ASCII letters, digits, "_" and "."; 3 to 20
 * characters; starts with a letter; no "..", no "." at the end. ASCII only, so a handle cannot imitate another with
 * look-alike letters from other scripts. Pure; the Android HandlePolicy mirrors it.
 */
public final class HandlePolicy {

    public static final int MIN_LENGTH = 3;
    public static final int MAX_LENGTH = 20;

    private static final Pattern FORMAT = Pattern.compile("^[a-z][a-z0-9_.]{1,18}[a-z0-9_]$");

    /** Words nobody may use as a handle (alone, or with dots/underscores/digits around them), to stop impersonation. */
    private static final Set<String> RESERVED = Set.of(
            "admin", "administrator", "moderator", "mod", "support", "help", "official", "team", "staff", "security",
            "safesphere", "kurinji", "system", "root", "null", "undefined", "me", "you", "anonymous", "deleted",
            "police", "emergency", "sos");

    public enum Result { OK, INVALID_FORMAT, RESERVED }

    private HandlePolicy() {
    }

    /** Trimmed and lower-cased (ASCII rules only); null stays null. A leading "@" is dropped. */
    public static String normalize(String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        if (value.startsWith("@")) value = value.substring(1);
        return value.toLowerCase(Locale.ROOT);
    }

    public static Result check(String normalized) {
        if (normalized == null || !FORMAT.matcher(normalized).matches() || normalized.contains("..")) {
            return Result.INVALID_FORMAT;
        }
        String core = normalized.replaceAll("[0-9_.]", "");
        if (RESERVED.contains(normalized) || RESERVED.contains(core)) return Result.RESERVED;
        return Result.OK;
    }
}
