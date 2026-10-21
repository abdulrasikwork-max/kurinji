package com.safesphere.backend.emergency.entity;

/** Where the phone got a location fix from. Stored as text (migration V4 checks the allowed values). */
public enum LocationSource {
    FUSED,
    LAST_KNOWN,
    GPS,
    NETWORK,
    UNKNOWN
}
