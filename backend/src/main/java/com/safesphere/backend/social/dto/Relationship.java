package com.safesphere.backend.social.dto;

/**
 * Phase 13A: how the viewer stands with the person whose profile they see. A request the viewer declined shows as NONE
 * to the viewer; to its sender it still shows as REQUESTED (a decline is silent).
 */
public enum Relationship {
    SELF,
    NONE,
    REQUESTED,
    REQUEST_RECEIVED,
    CONNECTED
}
