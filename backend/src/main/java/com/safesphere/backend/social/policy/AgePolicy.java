package com.safesphere.backend.social.policy;

import java.time.LocalDate;
import java.time.Period;

/**
 * Phase 13A (decision D6): the app is for people aged 18 and over. Pure: the caller passes "today" (the server's UTC
 * date, so the phone and the server agree on the same rule). The date of birth is only checked, never stored.
 *
 * Someone born on 29 February turns 18 on 1 March in a year without a 29 February (Period.between counts it so).
 */
public final class AgePolicy {

    public static final int MINIMUM_AGE = 18;
    /** Older than this is treated as a typing mistake, not an age. */
    public static final int MAXIMUM_AGE = 120;

    public enum Result { ADULT, UNDER_AGE, INVALID }

    private AgePolicy() {
    }

    public static Result check(LocalDate dateOfBirth, LocalDate today) {
        if (dateOfBirth == null || dateOfBirth.isAfter(today)) return Result.INVALID;
        int years = Period.between(dateOfBirth, today).getYears();
        if (years > MAXIMUM_AGE) return Result.INVALID;
        return years >= MINIMUM_AGE ? Result.ADULT : Result.UNDER_AGE;
    }
}
