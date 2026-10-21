package com.safesphere.backend.common.exception;

import org.springframework.http.HttpStatus;

/** 401 Unauthorized: a token was valid but the account behind it can no longer be used. */
public class UnauthorizedException extends ApiException {

    public UnauthorizedException(String message) {
        super(HttpStatus.UNAUTHORIZED, message);
    }
}
