package com.safesphere.backend.emergency.dto;

/**
 * Result of a batch upload. received = points in the request; stored = new rows written.
 * The difference is points the server already had (a re-sent batch), which are skipped, not errors.
 */
public record LocationUploadResponse(int received, int stored) {
}
