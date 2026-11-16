package com.safesphere.backend.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Phase N3: a verification request from a phone number an admin has banned from the community. 403 with the code
 * COMMUNITY_BANNED. Only the person holding that number's SIM can be signed in with it, so this tells nobody else
 * anything. Sign-in and every safety feature keep working for this person.
 */
public class CommunityBannedException extends ApiException {

    public static final String CODE = "COMMUNITY_BANNED";
    public static final String MESSAGE = "An admin removed your access to the MIET community on IRL. "
            + "Your safety features keep working.";

    public CommunityBannedException() {
        super(HttpStatus.FORBIDDEN, MESSAGE, CODE);
    }
}
