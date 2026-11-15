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
            @Size(max = 1000, message = "Bio is too long") String bio,
            @Size(max = 6, message = "Choose at most 6 languages") List<String> languages,
            @Size(max = 10, message = "Choose at most 10 interests") List<String> interests,
            @Size(max = 200, message = "City is too long") String city) {
    }

    /**
     * The caller's own profile. [handleChangeAvailableAt] is set while the handle cannot change yet. Phase 17E: [photo] =
     * the key of the profile photo (null = none; GET /api/profiles/{publicId}/photo/{photo}).
     */
    public record OwnProfileResponse(String publicId, String handle, String displayName, String bio,
                                     List<String> languages, List<String> interests, String city,
                                     Instant handleChangeAvailableAt, String photo) {
    }

    /**
     * Someone's profile as the viewer may see it. Phase 17E: [photo] as in OwnProfileResponse.
     */
    public record PersonProfileResponse(String publicId, String handle, String displayName, String bio,
                                        List<String> languages, List<String> interests, String city,
                                        Relationship relationship, String photo) {
    }

    /**
     * A person in a list (connections, requests, blocks, mutes) and the author of a post or a perspective. Phase 17E:
     * [photo] = the profile photo's key or null.
     */
    public record PersonSummary(String publicId, String handle, String displayName, String photo) {
    }

}
