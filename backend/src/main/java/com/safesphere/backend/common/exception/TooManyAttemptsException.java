package com.safesphere.backend.common.exception;

import org.springframework.http.HttpStatus;

/** 429 Too Many Requests: too many failed attempts (for example wrong codes); try again later. */
public class TooManyAttemptsException extends ApiException {

    public TooManyAttemptsException(String message) {
        super(HttpStatus.TOO_MANY_REQUESTS, message);
    }
}
