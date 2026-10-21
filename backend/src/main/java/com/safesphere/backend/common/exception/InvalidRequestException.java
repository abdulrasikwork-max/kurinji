package com.safesphere.backend.common.exception;

import org.springframework.http.HttpStatus;

/** 400 Bad Request for rules that annotations cannot express. */
public class InvalidRequestException extends ApiException {

    public InvalidRequestException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }
}
