package com.safesphere.backend.social.service;

import com.safesphere.backend.social.entity.Conversation;
import com.safesphere.backend.social.entity.Moment;
import com.safesphere.backend.social.entity.MomentEnums.Kind;
import com.safesphere.backend.social.entity.PlanPerson;
import com.safesphere.backend.social.entity.ResponseEnums.ConversationKind;
import com.safesphere.backend.social.entity.ResponseEnums.PlanState;
import com.safesphere.backend.social.entity.ResponseEnums.PrivateReplies;
import com.safesphere.backend.social.entity.SocialSettings;
import com.safesphere.backend.social.policy.ConversationPolicy;
import com.safesphere.backend.social.repository.ConversationRepository;
import com.safesphere.backend.social.repository.PlanPersonRepository;
import com.safesphere.backend.social.repository.SocialSettingsRepository;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Phase N5: what a viewer has under a page of moments besides taps (N-91, N-92): the plan they may open (its author and
 * the people in it), whether they may start a private reply now (the author's setting, the moment on Today), and their
 * private reply with the author once it exists. The caller already checked that the viewer may see each moment.
 */
@Component
public class PlanSpaces {

    /**
     * [planId] = the plan's conversation, for the author and the people in it once it has opened; [canReply] = the viewer
     * may start a private reply now (null for the author); [replyId] = their private reply with the author, if any.
     */
    public record Space(String planId, Boolean canReply, String replyId) {
    }

    private final ConversationRepository conversations;
    private final PlanPersonRepository planPeople;
    private final SocialSettingsRepository settings;
    private final Relationships relationships;

    public PlanSpaces(ConversationRepository conversations, PlanPersonRepository planPeople,
                      SocialSettingsRepository settings, Relationships relationships) {
        this.conversations = conversations;
        this.planPeople = planPeople;
        this.settings = settings;
        this.relationships = relationships;
    }

    public Map<Long, Space> forViewer(long viewerId, List<Moment> moments, LocalDateTime now) {
        Map<Long, Space> out = new HashMap<>();
        if (moments.isEmpty()) return out;
        List<Long> ids = moments.stream().map(Moment::getId).toList();
        List<Conversation> spaces = conversations.findByMomentIdIn(ids);
        Map<Long, Conversation> planOf = new HashMap<>();
        Map<Long, Conversation> replyOf = new HashMap<>();
        for (Conversation c : spaces) {
            if (c.getKind() == ConversationKind.PLAN) planOf.put(c.getMomentId(), c);
            else if (c.getReplierId() == viewerId) replyOf.put(c.getMomentId(), c);
        }
        Set<Long> inPlans = planPeople.findByUserIdAndMomentIdIn(viewerId, ids).stream()
                .filter(p -> p.getState() == PlanState.IN).map(PlanPerson::getMomentId).collect(Collectors.toSet());
        Set<Long> authors = moments.stream().map(Moment::getAuthorId).filter(a -> a != viewerId).collect(Collectors.toSet());
        Map<Long, PrivateReplies> settingOf = authors.isEmpty() ? Map.of() : settings.findByUserIdIn(authors).stream()
                .collect(Collectors.toMap(SocialSettings::getUserId, SocialSettings::getPrivateReplies));
        Set<Long> connected = authors.isEmpty() ? Set.of() : relationships.viewer(viewerId).connected();

        for (Moment m : moments) {
            boolean mine = m.getAuthorId() == viewerId;
            Conversation plan = planOf.get(m.getId());
            String planId = m.getKind() == Kind.WHOS_IN && plan != null && (mine || inPlans.contains(m.getId()))
                    ? plan.getPublicId() : null;
            if (mine) {
                out.put(m.getId(), new Space(planId, null, null));
                continue;
            }
            Conversation reply = replyOf.get(m.getId());
            boolean live = m.isLive(now) && m.getModeration().visibleToOthers();
            boolean can = reply == null && live && ConversationPolicy.allows(settingOf.get(m.getAuthorId()),
                    connected.contains(m.getAuthorId()));
            out.put(m.getId(), new Space(planId, can, reply == null ? null : reply.getPublicId()));
        }
        return out;
    }
}
