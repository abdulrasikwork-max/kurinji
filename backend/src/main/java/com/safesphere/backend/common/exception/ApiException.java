package com.safesphere.backend.common.exception;

import org.springframework.http.HttpStatus;

/** Base class for errors we throw on purpose. GlobalExceptionHandler turns them into ApiError JSON. */
public abstract class ApiException extends RuntimeException {

    private final HttpStatus status;
    /** V25: a stable code the app may react to (ApiError.code); null for most errors. */
    private final String code;

    protected ApiException(HttpStatus status, String message) {
        this(status, message, null);
    }

    protected ApiException(HttpStatus status, String message, String code) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
