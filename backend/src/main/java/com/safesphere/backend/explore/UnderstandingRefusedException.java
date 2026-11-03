package com.safesphere.backend.explore;

import com.safesphere.backend.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Phase 17G-3: the optional understanding cannot answer. 503 with a stable code; the app reacts to ANY failure the same
 * way (it silently uses the phone's word list), so the two codes only tell it whether to keep offering the switch.
 * The message never says why (a cap, a pause, a provider outage or a refused payment are the operator's business).
 */
public class UnderstandingRefusedException extends ApiException {

    public static final String OFF = "UNDERSTANDING_OFF";
    public static final String UNAVAILABLE = "UNDERSTANDING_UNAVAILABLE";

    private UnderstandingRefusedException(String message, String code) {
        super(HttpStatus.SERVICE_UNAVAILABLE, message, code);
    }

    public static UnderstandingRefusedException off() {
        return new UnderstandingRefusedException("Smart understanding is not available", OFF);
    }

    public static UnderstandingRefusedException unavailable() {
        return new UnderstandingRefusedException("Smart understanding is not available right now", UNAVAILABLE);
    }
}
