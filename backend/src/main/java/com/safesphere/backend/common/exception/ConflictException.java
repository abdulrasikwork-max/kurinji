package com.safesphere.backend.common.exception;

import org.springframework.http.HttpStatus;

/** 409 Conflict: the request is valid but clashes with the current state (duplicate, already active...). */
public class ConflictException extends ApiException {

    public ConflictException(String message) {
        super(HttpStatus.CONFLICT, message);
    }
}
