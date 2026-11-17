package com.safesphere.backend.social.service;

import com.safesphere.backend.social.entity.Moment;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Phase N5: what a viewer has under a page of moments besides taps: the plan they may open and their private reply with
 * the author (N-91, N-92). Conversations arrive in unit B2; until then nobody has one and nobody can start one.
 */
@Component
public class PlanSpaces {

    /**
     * [planId] = the plan's conversation, for the author and the people in it once it has opened; [canReply] = the viewer
     * may start a private reply now; [replyId] = their private reply with the author, once it exists.
     */
    public record Space(String planId, Boolean canReply, String replyId) {
    }

    public Map<Long, Space> forViewer(long viewerId, List<Moment> moments, LocalDateTime now) {
        return Map.of();
    }
}
