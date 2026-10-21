package com.safesphere.backend.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 403 Forbidden: the caller is known but may not do this (Phase 7: community contributions paused by a moderator,
 * a moderator acting on their own case or appeal). The message is ours and safe to show.
 */
public class ForbiddenException extends ApiException {

    public ForbiddenException(String message) {
        super(HttpStatus.FORBIDDEN, message);
    }
}
