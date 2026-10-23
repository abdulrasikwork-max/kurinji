package com.safesphere.backend.social.entity;

/** Phase 13A (V20). A DECLINED request is shown to its sender as still pending (a decline is silent). */
public enum ConnectionStatus {
    PENDING,
    ACCEPTED,
    DECLINED
}
