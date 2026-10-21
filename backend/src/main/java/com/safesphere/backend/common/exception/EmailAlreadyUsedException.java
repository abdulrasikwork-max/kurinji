package com.safesphere.backend.common.exception;

import org.springframework.http.HttpStatus;

/** 409 Conflict: registration with an email that already has an account. */
public class EmailAlreadyUsedException extends ApiException {

    public EmailAlreadyUsedException() {
        super(HttpStatus.CONFLICT, "An account with this email already exists");
    }
}
