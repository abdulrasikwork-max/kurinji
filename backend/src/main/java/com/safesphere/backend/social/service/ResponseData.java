package com.safesphere.backend.social.service;

import com.safesphere.backend.social.entity.Conversation;
import com.safesphere.backend.social.repository.ConversationRepository;
import com.safesphere.backend.social.repository.MessageRepository;
import com.safesphere.backend.social.repository.MomentSignalRepository;
import com.safesphere.backend.social.repository.PlanPersonRepository;
import java.util.Collection;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Phase N5 (N-98): everything people said or did under some moments: taps, asks and plan places, plans, private
 * replies and their messages. Deleted with a moment, and 7 days after a moment's end even when it is kept. MySQL
 * cascades these rows too; it is done here so the rule never depends on the database (the test database has no
 * foreign keys). Must run inside the caller's transaction.
 */
@Component
public class ResponseData {

    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final PlanPersonRepository planPeople;
    private final MomentSignalRepository signals;
    private final org.springframework.beans.factory.ObjectProvider<MessageCleanup> cleanups;

    public ResponseData(ConversationRepository conversations, MessageRepository messages,
                        PlanPersonRepository planPeople, MomentSignalRepository signals,
                        org.springframework.beans.factory.ObjectProvider<MessageCleanup> cleanups) {
        this.conversations = conversations;
        this.messages = messages;
        this.planPeople = planPeople;
        this.signals = signals;
        this.cleanups = cleanups;
    }

    /**
     * Something outside social that keeps rows about messages (the moderation package: reports, cases, assessments)
     * removes them first. Social only knows this port, never the moderation package (SocialIsolationTest).
     */
    public interface MessageCleanup {
        void messagesGoing(Collection<Long> messageIds);
    }

    public void deleteFor(Collection<Long> momentIds) {
        if (momentIds.isEmpty()) return;
        List<Long> conversationIds = conversations.findByMomentIdIn(momentIds).stream().map(Conversation::getId).toList();
        if (!conversationIds.isEmpty()) {
            List<Long> messageIds = messages.findByConversationIdIn(conversationIds).stream()
                    .map(com.safesphere.backend.social.entity.Message::getId).toList();
            if (!messageIds.isEmpty()) cleanups.orderedStream().forEach(c -> c.messagesGoing(messageIds));
            messages.deleteByConversationIdIn(conversationIds);
            conversations.deleteByMomentIdIn(momentIds);
        }
        planPeople.deleteByMomentIdIn(momentIds);
        signals.deleteByMomentIdIn(momentIds);
    }
}
