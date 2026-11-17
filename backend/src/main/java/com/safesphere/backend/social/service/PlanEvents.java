package com.safesphere.backend.social.service;

import com.safesphere.backend.social.entity.Conversation;
import com.safesphere.backend.social.entity.Message;
import com.safesphere.backend.social.entity.Moment;
import com.safesphere.backend.social.entity.ResponseEnums.ConversationKind;
import com.safesphere.backend.social.entity.ResponseEnums.MessageEvent;
import com.safesphere.backend.social.policy.ResponsePolicy;
import com.safesphere.backend.social.repository.ConversationRepository;
import com.safesphere.backend.social.repository.MessageRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Phase N5 (N-90, N-92): what happens to a plan's conversation when its people change. Called inside the write that
 * changed them (the moment row is locked by the caller, so the plan is opened once).
 */
@Component
public class PlanEvents {

    private final ConversationRepository conversations;
    private final MessageRepository messages;

    public PlanEvents(ConversationRepository conversations, MessageRepository messages) {
        this.conversations = conversations;
        this.messages = messages;
    }

    /** Someone was added: the plan opens with the first one, and says "… is in". */
    public void added(Moment plan, long userId, LocalDateTime now) {
        Conversation conversation = planOf(plan).orElseGet(() -> conversations.saveAndFlush(Conversation.plan(plan.getId(),
                now, ResponsePolicy.planClosesAt(plan.getPlanAt(), plan.getExpiresAt(), plan.getCalledOffAt()))));
        messages.saveAndFlush(Message.system(conversation.getId(), userId, MessageEvent.IN, now));
    }

    /** Someone who was in left: "… can't make it" (only while the plan is open). */
    public void left(Moment plan, long userId, LocalDateTime now) {
        planOf(plan).filter(c -> c.isOpen(now)).ifPresent(c ->
                messages.saveAndFlush(Message.system(c.getId(), userId, MessageEvent.LEFT, now)));
    }

    /** The author removed someone: nobody else is told (N-90: no line); they simply lose the plan. */
    public void removed(Moment plan, long userId, LocalDateTime now) {
    }

    /** The author called it off: the plan says so and closes now. */
    public void calledOff(Moment plan, LocalDateTime now) {
        planOf(plan).ifPresent(c -> {
            if (c.isOpen(now)) messages.saveAndFlush(Message.system(c.getId(), plan.getAuthorId(), MessageEvent.CALLED_OFF, now));
            c.closeBy(now);
            conversations.saveAndFlush(c);
        });
    }

    /** N-92: someone shared This happened: the plan says so. */
    public void happened(Moment plan, long userId, LocalDateTime now) {
        planOf(plan).filter(c -> c.isOpen(now)).ifPresent(c ->
                messages.saveAndFlush(Message.system(c.getId(), userId, MessageEvent.HAPPENED, now)));
    }

    public Optional<Conversation> planOf(Moment plan) {
        return conversations.findByMomentIdAndKind(plan.getId(), ConversationKind.PLAN);
    }
}
