package com.safesphere.backend.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Phase 17A: a social write (post, perspective, connection) from an account without a profile yet. 409 with the same
 * words as before plus the code PROFILE_NEEDED, so the app can offer "Make your profile" right where the person is.
 */
public class ProfileNeededException extends ApiException {

    public static final String CODE = "PROFILE_NEEDED";
    public static final String MESSAGE = "Make your profile first";

    public ProfileNeededException() {
        super(HttpStatus.CONFLICT, MESSAGE, CODE);
    }
}
