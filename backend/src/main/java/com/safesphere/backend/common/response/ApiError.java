package com.safesphere.backend.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * The one JSON shape every error uses, so the Android app only needs to understand one format.
 * fieldErrors is only present for validation failures (field name -> message).
 * code (V25) is present only where the app must react to one specific refusal (e.g. EMAIL_NOT_VERIFIED); a stable
 * upper-case code, never an exception name or internal detail.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        Map<String, String> fieldErrors,
        String code
) {

    public static ApiError of(HttpStatus status, String message, String path) {
        return of(status, message, path, null);
    }

    public static ApiError of(HttpStatus status, String message, String path, Map<String, String> fieldErrors) {
        return new ApiError(Instant.now(), status.value(), status.getReasonPhrase(), message, path, fieldErrors, null);
    }

    public static ApiError withCode(HttpStatus status, String message, String path, String code) {
        return new ApiError(Instant.now(), status.value(), status.getReasonPhrase(), message, path, null, code);
    }
}
