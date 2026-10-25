package com.safesphere.backend.social.dto;

import com.safesphere.backend.social.dto.ProfileDtos.PersonSummary;
import jakarta.validation.constraints.Size;

/** Phase 13B: Perspectives. No counts, no timestamps (a band), no list of who marked anything. */
public final class CommentDtos {

    private CommentDtos() {
    }

    /** Body of POST /api/posts/{id}/comments. [parentId] only for a REPLY. */
    public record CommentRequest(
            @Size(max = 12, message = "Unknown kind") String kind,
            @Size(max = 5000, message = "Text is too long") String body,
            @Size(max = 40, message = "Unknown perspective") String parentId) {
    }

    /**
     * One perspective. [hidden] is true only for its writer and the post's author (Q5: "Hidden by the author"); nobody
     * else ever receives a hidden one. [canHide] / [canMarkHelped] tell the post's author which controls apply.
     * Phase 13D: [awaitingApproval] only for its writer and the post's author (Q6; [canApprove] for the post's author);
     * [moderation] only for its writer; [collapsed] for everyone else (null / UNDER_REVIEW / BY_MODERATOR).
     * Phase 13E: as posts ([collapsed] BY_SYSTEM, [strongLanguage], [supportSuggested] in the writer's create answer).
     */
    public record CommentResponse(String publicId, String kind, PersonSummary author, boolean own, String body,
                                  String postedPeriod, boolean hidden, boolean helped, String parentId, boolean canHide,
                                  boolean canMarkHelped, boolean awaitingApproval, boolean canApprove,
                                  PostDtos.ModerationView moderation, String collapsed, boolean strongLanguage,
                                  Boolean supportSuggested) {
    }
}
