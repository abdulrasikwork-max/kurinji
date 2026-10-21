package com.safesphere.backend.common.exception;

import org.springframework.http.HttpStatus;

/** Base class for errors we throw on purpose. GlobalExceptionHandler turns them into ApiError JSON. */
public abstract class ApiException extends RuntimeException {

    private final HttpStatus status;

    protected ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
