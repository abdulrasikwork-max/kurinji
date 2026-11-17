package com.safesphere.backend.social.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/**
 * Phase 13A: profile requests and answers. No answer carries a count of anything (connections, requests, look-ups):
 * the platform shows no numbers about people (ProfileApiIntegrationTest checks the field names).
 */
public final class ProfileDtos {

    private ProfileDtos() {
    }

    /**
     * Body of PUT /api/profiles/me. Lengths are checked exactly (in characters) by ProfileTextPolicy. Phase N2: no name
     * here; every profile shows the account's real name ([OwnProfileResponse#displayName] etc. carry it).
     */
    public record ProfileRequest(
            @NotNull(message = "Handle is required") @Size(max = 40, message = "Handle is too long") String handle,
            @Size(max = 1000, message = "Bio is too long") String bio) {
    }

    /**
     * The caller's own profile. [handleChangeAvailableAt] is set while the handle cannot change yet. [photo] = the key of
     * the profile photo (null = none; GET /api/profiles/{publicId}/photo/{photo}). Phase N4 (N-51): [programme] (its
     * code) and [graduationYear] from the verified membership, null without an active one; [newMemberUntil] = when the
     * 7-day new-member period ends (N-56), null once established or without a membership.
     */
    public record OwnProfileResponse(String publicId, String handle, String displayName, String bio,
                                     String programme, Integer graduationYear, Instant newMemberUntil,
                                     Instant handleChangeAvailableAt, String photo) {
    }

    /**
     * Someone's profile as the viewer may see it. Phase N4 (N-51): [programme] and [graduationYear] as a verifier saw
     * them on the ID card. No city, no languages, no interests; never a count of anything.
     */
    public record PersonProfileResponse(String publicId, String handle, String displayName, String bio,
                                        String programme, Integer graduationYear, Relationship relationship,
                                        String photo) {
    }

    /**
     * A person in a list (connections, requests, blocks, mutes). [photo] = the profile photo's key or null.
     */
    public record PersonSummary(String publicId, String handle, String displayName, String photo) {
    }

}
