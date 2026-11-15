package com.safesphere.backend.user.service;

import java.text.Normalizer;

/**
 * Phase N2 (decision N-3): the person's real name, the one name IRL shows (no separate profile name since V33).
 * Pure; the Android app checks the same rule before sending (RealNamePolicy.kt).
 *
 * <ul>
 *   <li>Cleaned: Unicode NFC, spaces around removed, runs of white space become one space.</li>
 *   <li>2 to 60 characters (code points) after cleaning, with at least 2 letters.</li>
 *   <li>Allowed: letters of any script, the marks that complete them (Tamil vowel signs and pulli are marks), the
 *       zero-width joiners Indic scripts use, spaces, and the three name signs . ' - (also the typographic ’).</li>
 *   <li>Not allowed: digits, emoji, symbols, other punctuation, control characters: a real name has none.</li>
 * </ul>
 * It cannot tell a real name from an invented one; the college ID check of N3 does that.
 */
public final class RealNamePolicy {

    public static final int MIN = 2;
    public static final int MAX = 60;

    public enum Result { OK, TOO_SHORT, TOO_LONG, NOT_A_NAME }

    private RealNamePolicy() {
    }

    /** The name as it will be stored, or "" for null. */
    public static String clean(String raw) {
        if (raw == null) return "";
        String nfc = Normalizer.normalize(raw, Normalizer.Form.NFC);
        return nfc.strip().replaceAll("\\s+", " ");
    }

    /** Checks an already [clean]ed name. */
    public static Result check(String cleaned) {
        if (cleaned == null) return Result.TOO_SHORT;
        int length = cleaned.codePointCount(0, cleaned.length());
        if (length > MAX) return Result.TOO_LONG;
        int letters = 0;
        for (int i = 0; i < cleaned.length(); ) {
            int cp = cleaned.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isLetter(cp)) {
                letters++;
            } else if (!allowedOther(cp)) {
                return Result.NOT_A_NAME;
            }
        }
        if (length < MIN || letters < MIN) return Result.TOO_SHORT;
        return Result.OK;
    }

    /** The words the API answers with for a refused name. */
    public static String message(Result result) {
        return switch (result) {
            case OK -> "";
            case TOO_SHORT -> "Enter your name as it is on your college ID";
            case TOO_LONG -> "Your name can be at most " + MAX + " characters";
            case NOT_A_NAME -> "Use letters only (spaces and . ' - are fine)";
        };
    }

    private static boolean allowedOther(int cp) {
        int type = Character.getType(cp);
        return type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK
                || cp == ' ' || cp == '.' || cp == '\'' || cp == '-' || cp == '’'
                || cp == '‌' || cp == '‍';
    }
}
