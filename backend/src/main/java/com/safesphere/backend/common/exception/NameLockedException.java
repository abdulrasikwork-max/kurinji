package com.safesphere.backend.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Phase N3 (N-24): the real name was checked against the college ID, so the person can no longer change it themselves;
 * an admin corrects a wrong spelling (logged and shown to the person). 409 with the code NAME_LOCKED.
 */
public class NameLockedException extends ApiException {

    public static final String CODE = "NAME_LOCKED";
    public static final String MESSAGE = "Your name was checked against your college ID, so it can no longer be changed "
            + "here. If it is spelled wrongly, ask an IRL admin to correct it.";

    public NameLockedException() {
        super(HttpStatus.CONFLICT, MESSAGE, CODE);
    }
}
