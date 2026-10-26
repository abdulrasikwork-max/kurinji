package com.safesphere.backend.common.exception;

import org.springframework.http.HttpStatus;

/**
 * V25: the right password for an account whose e-mail is not verified yet. 403 with code EMAIL_NOT_VERIFIED, and no
 * session. Only someone who knows the password ever sees it (a wrong password stays the generic 401).
 */
public class EmailNotVerifiedException extends ApiException {

    public static final String CODE = "EMAIL_NOT_VERIFIED";

    public EmailNotVerifiedException() {
        super(HttpStatus.FORBIDDEN, "Please verify your email before signing in", CODE);
    }
}
