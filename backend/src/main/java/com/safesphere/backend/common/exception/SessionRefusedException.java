package com.safesphere.backend.common.exception;

/**
 * Phase 11 C1: 401 for a refresh that was refused. [reason] (session_revoked, account_deleted, account_unavailable) is
 * sent as the RFC 6750 error_description, like the per-request account check, so the phone can tell "sign in again"
 * from "this account is gone".
 */
public class SessionRefusedException extends UnauthorizedException {

    private final String reason;

    public SessionRefusedException(String reason) {
        super("Please sign in again on this phone.");
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }
}
