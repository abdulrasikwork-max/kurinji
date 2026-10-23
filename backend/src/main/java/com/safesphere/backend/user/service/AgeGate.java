package com.safesphere.backend.user.service;

import com.safesphere.backend.common.exception.InvalidRequestException;
import com.safesphere.backend.social.policy.AgePolicy;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.stereotype.Component;

/**
 * Phase 13A: applies [AgePolicy] with the server's UTC date and turns a refusal into a 400 with our own words. Used at
 * registration and by the one-time confirmation for accounts created before V20. The date of birth is not kept or
 * logged anywhere.
 */
@Component
public class AgeGate {

    static final String UNDER_AGE_MESSAGE = "You must be 18 or older to use this app";
    static final String INVALID_MESSAGE = "Enter a valid date of birth";

    private final Clock clock;

    public AgeGate(Clock clock) {
        this.clock = clock;
    }

    public void requireAdult(LocalDate dateOfBirth) {
        switch (AgePolicy.check(dateOfBirth, LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC))) {
            case ADULT -> {
            }
            case UNDER_AGE -> throw new InvalidRequestException(UNDER_AGE_MESSAGE);
            case INVALID -> throw new InvalidRequestException(INVALID_MESSAGE);
        }
    }
}
