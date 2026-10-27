package com.safesphere.backend.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Phase 17A: a social call from an account that has not declared an adult date of birth yet (accounts created before
 * V20). 409 with the same words as before plus the code AGE_NOT_CONFIRMED, so the app can open its one age screen
 * instead of showing the refusal on every social screen.
 */
public class AgeNotConfirmedException extends ApiException {

    public static final String CODE = "AGE_NOT_CONFIRMED";
    public static final String MESSAGE = "Confirm your age to use this";

    public AgeNotConfirmedException() {
        super(HttpStatus.CONFLICT, MESSAGE, CODE);
    }
}
