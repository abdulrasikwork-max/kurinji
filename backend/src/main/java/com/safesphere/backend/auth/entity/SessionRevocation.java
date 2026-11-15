package com.safesphere.backend.auth.entity;

/** Why a device session ended (V19 CHECK ck_device_sessions_reason). */
public enum SessionRevocation {
    /** The person signed out on that phone. */
    SIGNED_OUT,
    /** Before N2: the password was changed on another sign-in (kept for the rows written then). */
    PASSWORD_CHANGED,
    /** N2 (N-26): the person signed out all their other phones from one sign-in. */
    OTHERS_SIGNED_OUT,
    /** A replaced refresh secret was shown again after the grace period: possibly copied. */
    REUSE_DETECTED,
    /** The account was switched off by the operator. */
    ACCOUNT_DISABLED,
    /** Not used for longer than the idle timeout. */
    EXPIRED
}
