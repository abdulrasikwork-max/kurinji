package com.safesphere.backend.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Phase N3: a community read or write by a signed-in account that is not an active member (not verified yet, pending,
 * graduated, ended or banned). 403 with the stable code MEMBERSHIP_NEEDED, so the app can show "Get verified" (or the
 * person's state) right where they are. Safety features never throw this.
 */
public class MembershipNeededException extends ApiException {

    public static final String CODE = "MEMBERSHIP_NEEDED";
    public static final String MESSAGE = "This is for verified MIET members. You can get verified from You.";

    public MembershipNeededException() {
        super(HttpStatus.FORBIDDEN, MESSAGE, CODE);
    }
}
