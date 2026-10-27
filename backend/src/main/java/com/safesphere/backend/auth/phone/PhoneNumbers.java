package com.safesphere.backend.auth.phone;

import java.util.regex.Pattern;

/**
 * Phase 17B: the one rule for mobile numbers (pure, unit-tested; the Android app has the same rule in PhonePolicy).
 * India only for now (the launch community, and the only country Fast2SMS delivers to): a 10-digit mobile number that
 * starts with 6, 7, 8 or 9, typed with or without +91 / 91 / 0 and with spaces, dashes or brackets. Stored as E.164,
 * "+91XXXXXXXXXX" (V26 CHECK).
 */
public final class PhoneNumbers {

    private static final Pattern MOBILE = Pattern.compile("^[6-9][0-9]{9}$");
    private static final Pattern E164 = Pattern.compile("^\\+91[6-9][0-9]{9}$");

    private PhoneNumbers() {
    }

    /** "+91XXXXXXXXXX", or null when [raw] is not an Indian mobile number. */
    public static String normalize(String raw) {
        if (raw == null) return null;
        String digits = raw.strip().replaceAll("[\\s().-]", "");
        if (digits.startsWith("+91")) digits = digits.substring(3);
        else if (digits.startsWith("+")) return null; // another country: not supported yet
        else if (digits.length() == 12 && digits.startsWith("91")) digits = digits.substring(2);
        else if (digits.length() == 11 && digits.startsWith("0")) digits = digits.substring(1);
        return MOBILE.matcher(digits).matches() ? "+91" + digits : null;
    }

    public static boolean isE164(String value) {
        return value != null && E164.matcher(value).matches();
    }

    /** The 10 national digits ("9876543210"), as Fast2SMS expects them. */
    public static String national(String e164) {
        if (!isE164(e164)) throw new IllegalArgumentException("Not a stored number");
        return e164.substring(3);
    }

    /** What the app may show: the last 4 digits only ("••••••3210"). */
    public static String hint(String e164) {
        if (!isE164(e164)) return null;
        return "••••••" + e164.substring(e164.length() - 4);
    }
}
