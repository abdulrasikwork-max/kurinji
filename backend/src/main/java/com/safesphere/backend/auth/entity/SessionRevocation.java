package com.safesphere.backend.auth.entity;

/** Why a device session ended (V19 CHECK ck_device_sessions_reason). */
public enum SessionRevocation {
    /** The person signed out on that phone. */
    SIGNED_OUT,
    /** The password was changed on another sign-in. */
    PASSWORD_CHANGED,
    /** A replaced refresh secret was shown again after the grace period: possibly copied. */
    REUSE_DETECTED,
    /** The account was switched off by the operator. */
    ACCOUNT_DISABLED,
    /** Not used for longer than the idle timeout. */
    EXPIRED
}
