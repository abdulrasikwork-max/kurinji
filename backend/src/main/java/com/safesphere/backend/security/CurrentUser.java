package com.safesphere.backend.security;

import com.safesphere.backend.common.exception.UnauthorizedException;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The ONLY way controllers learn who is calling: from the verified JWT ("sub" claim), never from
 * the request body, URL or headers the client controls.
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static long id(Jwt jwt) {
        try {
            return Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException e) {
            throw new UnauthorizedException("Invalid token subject");
        }
    }
}
