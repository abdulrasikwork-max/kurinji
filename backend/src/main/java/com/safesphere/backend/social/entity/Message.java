package com.safesphere.backend.social.entity;

import com.safesphere.backend.social.entity.ContentModeration.ModerationReason;
import com.safesphere.backend.social.entity.ContentModeration.ModerationSource;
import com.safesphere.backend.social.entity.ContentModeration.ModerationState;
import com.safesphere.backend.social.entity.ResponseEnums.MessageEvent;
import com.safesphere.backend.social.entity.ResponseEnums.MessageKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Phase N5 (V36): one message in a conversation: words someone wrote (TEXT, 1 to 500 characters, never edited, never
 * unsent: N-91) or a quiet SYSTEM line ([event]). The moderation columns mean what they mean on moments and are written
 * by the moderation package only (HELD and REMOVED: only the writer sees it; COLLAPSED: behind a tap).
 */
@Entity
@Table(name = "messages",
        uniqueConstraints = @UniqueConstraint(name = "uk_messages_public_id", columnNames = "public_id"),
        indexes = @Index(name = "idx_messages_conversation", columnList = "conversation_id, created_at, id"))
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, updatable = false, length = 36)
    private String publicId;

    @Column(name = "conversation_id", nullable = false, updatable = false)
    private Long conversationId;

    @Column(name = "author_id", nullable = false, updatable = false)
    private Long authorId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 6)
    private MessageKind kind;

    @Column(updatable = false, length = 500)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(updatable = false, length = 10)
    private MessageEvent event;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "moderation", nullable = false, length = 9)
    private ModerationState moderation = ModerationState.NONE;

    @Enumerated(EnumType.STRING)
    @Column(name = "moderation_reason", length = 20)
    private ModerationReason moderationReason;

    @Column(name = "moderation_decision", length = 36)
    private String moderationDecision;

    @Enumerated(EnumType.STRING)
    @Column(name = "moderation_source", length = 9)
    private ModerationSource moderationSource;

    @Column(name = "moderation_spans", length = 200)
    private String moderationSpans;

    @Column(name = "in_review", nullable = false)
    private boolean inReview;

    /** Required by JPA. */
    protected Message() {
    }

    private Message(long conversationId, long authorId, MessageKind kind, String body, MessageEvent event,
                    LocalDateTime now) {
        this.publicId = UUID.randomUUID().toString();
        this.conversationId = conversationId;
        this.authorId = authorId;
        this.kind = kind;
        this.body = body;
        this.event = event;
        this.createdAt = now.truncatedTo(ChronoUnit.MICROS);
    }

    public static Message text(long conversationId, long authorId, String body, LocalDateTime now) {
        return new Message(conversationId, authorId, MessageKind.TEXT, body, null, now);
    }

    /** A quiet line; [actorId] = who it is about (who is in, who left, who called it off or shared This happened). */
    public static Message system(long conversationId, long actorId, MessageEvent event, LocalDateTime now) {
        return new Message(conversationId, actorId, MessageKind.SYSTEM, null, event, now);
    }

    /** A state set by reports (no decision) or by a moderator (a decision id). Moderation package only. */
    public void moderate(ModerationState state, ModerationReason reason, String decision) {
        this.moderation = state;
        this.moderationReason = state == ModerationState.NONE ? null : reason;
        this.moderationDecision = state == ModerationState.NONE ? null : decision;
        this.moderationSource = ModerationSource.of(state, decision);
        this.moderationSpans = null;
    }

    /** A state set by an automatic rule (COLLAPSED or HELD with a reason, or NONE). Never REMOVED. */
    public void moderateBySystem(ModerationState state, ModerationReason reason, String spans) {
        if (state == ModerationState.REMOVED) throw new IllegalArgumentException("the system never removes");
        this.moderation = state;
        this.moderationReason = state == ModerationState.NONE ? null : reason;
        this.moderationDecision = null;
        this.moderationSource = state == ModerationState.NONE ? null : ModerationSource.SYSTEM;
        this.moderationSpans = state == ModerationState.NONE ? null : spans;
    }

    public void setInReview(boolean inReview) {
        this.inReview = inReview;
    }

    public Long getId() {
        return id;
    }

    public String getPublicId() {
        return publicId;
    }

    public Long getConversationId() {
        return conversationId;
    }

    public Long getAuthorId() {
        return authorId;
    }

    public MessageKind getKind() {
        return kind;
    }

    public String getBody() {
        return body;
    }

    public MessageEvent getEvent() {
        return event;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public ModerationState getModeration() {
        return moderation;
    }

    public ModerationReason getModerationReason() {
        return moderationReason;
    }

    public String getModerationDecision() {
        return moderationDecision;
    }

    public ModerationSource getModerationSource() {
        return moderationSource;
    }

    public String getModerationSpans() {
        return moderationSpans;
    }

    public boolean isInReview() {
        return inReview;
    }
}
