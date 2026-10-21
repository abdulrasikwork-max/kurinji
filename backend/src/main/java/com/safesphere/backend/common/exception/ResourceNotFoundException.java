package com.safesphere.backend.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 404 Not Found. Also used when a record exists but belongs to ANOTHER user: the caller gets exactly
 * the same answer as for a record that does not exist, so ids cannot be probed.
 */
public class ResourceNotFoundException extends ApiException {

    public ResourceNotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, message);
    }
}
