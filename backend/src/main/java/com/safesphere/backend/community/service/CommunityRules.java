package com.safesphere.backend.community.service;

import com.safesphere.backend.community.entity.CommunityEnums.Programme;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Phase N3: the pure rules of verification (no Spring, no clock of its own; unit-tested in CommunityRulesTest).
 */
public final class CommunityRules {

    /** India: membership ends at the end of 31 July of the graduation year here (owner, 2026-11-16). */
    public static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    /**
     * A roll number after normalising: 4–15 letters or digits, at least one digit. No fixed pattern (MIET roll numbers
     * look like E1236003; the owner said not to assume a format).
     */
    private static final Pattern ROLL = Pattern.compile("^(?=.*[0-9])[A-Z0-9]{4,15}$");

    /** Short codes: 8 characters without 0/O, 1/I/L (read aloud or typed from another screen). */
    static final String CODE_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
    static final int CODE_LENGTH = 8;
    private static final Pattern CODE = Pattern.compile("^[" + CODE_ALPHABET + "]{" + CODE_LENGTH + "}$");

    private CommunityRules() {
    }

    /** Upper-case, without spaces and the separators people type (- / .); null for a blank or null input. */
    public static String normaliseRoll(String raw) {
        if (raw == null) return null;
        String out = raw.trim().toUpperCase(Locale.ROOT).replaceAll("[\\s\\-/.]", "");
        return out.isEmpty() ? null : out;
    }

    public static boolean isRoll(String normalised) {
        return normalised != null && ROLL.matcher(normalised).matches();
    }

    /** A new random code (8 characters of the alphabet above, about 40 bits). */
    public static String newCode(SecureRandom random) {
        StringBuilder out = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) out.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
        return out.toString();
    }

    /** "ABCD2345" → "ABCD-2345" (how the app shows it). */
    public static String display(String code) {
        return code.substring(0, 4) + "-" + code.substring(4);
    }

    /** What a person typed → the stored form, or null if it cannot be a code (O→0 and I/L→1 are NOT guessed). */
    public static String normaliseCode(String raw) {
        if (raw == null) return null;
        String out = raw.trim().toUpperCase(Locale.ROOT).replaceAll("[\\s\\-]", "");
        return CODE.matcher(out).matches() ? out : null;
    }

    /** The moment a membership with this graduation year ends: the end of 31 July in India, as UTC. */
    public static LocalDateTime endsAt(int graduationYear) {
        return LocalDate.of(graduationYear, 8, 1).atTime(LocalTime.MIDNIGHT).atZone(INDIA)
                .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    /** The earliest graduation year accepted at [nowUtc]: this year until its 31 July has ended, then the next. */
    public static int firstYear(LocalDateTime nowUtc) {
        int year = nowUtc.atOffset(ZoneOffset.UTC).atZoneSameInstant(INDIA).getYear();
        return endsAt(year).isAfter(nowUtc) ? year : year + 1;
    }

    /** The latest graduation year accepted for [programme] at [nowUtc]: this year + the programme's usual length. */
    public static int lastYear(Programme programme, LocalDateTime nowUtc) {
        int year = nowUtc.atOffset(ZoneOffset.UTC).atZoneSameInstant(INDIA).getYear();
        return Math.max(firstYear(nowUtc), year + programme.years());
    }

    public static boolean yearAllowed(Programme programme, int graduationYear, LocalDateTime nowUtc) {
        return graduationYear >= firstYear(nowUtc) && graduationYear <= lastYear(programme, nowUtc);
    }
}
