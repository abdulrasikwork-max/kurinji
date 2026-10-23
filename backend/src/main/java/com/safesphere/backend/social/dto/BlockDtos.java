package com.safesphere.backend.social.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Phase 13A: blocks and mutes. Lists answer with ProfileDtos.PersonSummary; nobody but the owner sees them. */
public final class BlockDtos {

    private BlockDtos() {
    }

    /** Body of POST /api/blocks and POST /api/mutes: the person's public profile id. */
    public record PersonTarget(@NotBlank(message = "Choose a person") @Size(max = 36) String profileId) {
    }
}
