package com.safesphere.backend.common.event;

/**
 * N7 (D28 = N-120): one or more sign-ins just ended (signed out, others signed out, refused at refresh). Published inside
 * the auth transaction; the push package removes the phones of ended sign-ins after the commit, so a token never outlives
 * its sign-in. Carries nothing (the listener finds every ended sign-in itself); auth never imports push.
 */
public record SessionsEnded() {
}
