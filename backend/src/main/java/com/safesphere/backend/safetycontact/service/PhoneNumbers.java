package com.safesphere.backend.safetycontact.service;

import com.safesphere.backend.common.exception.InvalidRequestException;
import java.util.regex.Pattern;

/**
 * Turns what a person types ("+91 98765-43210", "(022) 1234 5678") into one canonical form
 * ("+919876543210"), so the same number cannot be saved twice just because it was formatted differently.
 * Limitation: it does not know about countries, so "9876543210" and "+919876543210" count as different.
 */
public final class PhoneNumbers {

    /** Optional leading +, then 7 to 15 digits (the E.164 maximum). */
    private static final Pattern CANONICAL = Pattern.compile("^\\+?[0-9]{7,15}$");

    private PhoneNumbers() {
    }

    public static String normalize(String raw) {
        String compact = raw.trim().replaceAll("[\\s().-]", "");
        if (!CANONICAL.matcher(compact).matches()) {
            throw new InvalidRequestException("Enter a valid phone number");
        }
        return compact;
    }
}
