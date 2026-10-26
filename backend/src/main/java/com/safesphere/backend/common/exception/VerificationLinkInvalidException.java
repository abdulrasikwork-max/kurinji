package com.safesphere.backend.common.exception;

import org.springframework.http.HttpStatus;

/**
 * V25: one answer for every link that cannot verify anything (unknown, malformed, already used, replaced by a newer
 * e-mail, or expired), so the answer says nothing about which accounts or tokens exist.
 */
public class VerificationLinkInvalidException extends ApiException {

    public static final String CODE = "VERIFICATION_LINK_INVALID";

    public VerificationLinkInvalidException() {
        super(HttpStatus.BAD_REQUEST, "This link is not valid or has expired", CODE);
    }
}
