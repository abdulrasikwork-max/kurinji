package com.safesphere.backend.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 401 Unauthorized: wrong email OR wrong password. The message is deliberately identical for both,
 * so an attacker cannot use the response to find out which emails have accounts.
 */
public class InvalidCredentialsException extends ApiException {

    public InvalidCredentialsException() {
        super(HttpStatus.UNAUTHORIZED, "Invalid email or password");
    }
}
