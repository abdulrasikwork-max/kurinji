package com.safesphere.backend.social.entity;

import com.safesphere.backend.social.entity.ResponseEnums.ConversationKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Phase N5 (V36): a space of messages under one moment. A PLAN (N-92): the author and the people IN, one per Who's in?,
 * opened by the first Add. A REPLY (N-91): one person's private reply to the author, one per moment and person, strict
 * turns. Read-only from [closesAt]; a REPLY can also be ended early by either of the two ([endedAt]). Membership is not
 * stored here: it is the moment's author and, for a PLAN, the plan_people IN (one source of truth).
 */
@Entity
@Table(name = "conversations",
        uniqueConstraints = {@UniqueConstraint(name = "uk_conversations_public_id", columnNames = "public_id"),
                @UniqueConstraint(name = "uk_conversations_reply", columnNames = {"moment_id", "replier_id"})})
public class Conversation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, updatable = false, length = 36)
    private String publicId;

    @Column(name = "moment_id", nullable = false, updatable = false)
    private Long momentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 5)
    private ConversationKind kind;

    @Column(name = "replier_id", updatable = false)
    private Long replierId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "closes_at", nullable = false)
    private LocalDateTime closesAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    /** Required by JPA. */
    protected Conversation() {
    }

    private Conversation(long momentId, ConversationKind kind, Long replierId, LocalDateTime now, LocalDateTime closesAt) {
        this.publicId = UUID.randomUUID().toString();
        this.momentId = momentId;
        this.kind = kind;
        this.replierId = replierId;
        this.createdAt = now.truncatedTo(ChronoUnit.MICROS);
        LocalDateTime closes = closesAt.truncatedTo(ChronoUnit.MICROS);
        this.closesAt = closes.isAfter(this.createdAt) ? closes : this.createdAt.plusSeconds(1);
    }

    public static Conversation plan(long momentId, LocalDateTime now, LocalDateTime closesAt) {
        return new Conversation(momentId, ConversationKind.PLAN, null, now, closesAt);
    }

    public static Conversation reply(long momentId, long replierId, LocalDateTime now, LocalDateTime closesAt) {
        return new Conversation(momentId, ConversationKind.REPLY, replierId, now, closesAt);
    }

    /** Open for new messages at [now]: not past its close and not ended. */
    public boolean isOpen(LocalDateTime now) {
        return endedAt == null && closesAt.isAfter(now);
    }

    /** Closes earlier (a plan called off). Never later. */
    public void closeBy(LocalDateTime at) {
        LocalDateTime when = at.truncatedTo(ChronoUnit.MICROS);
        if (when.isBefore(closesAt)) this.closesAt = when.isAfter(createdAt) ? when : createdAt.plusSeconds(1);
    }

    /** N-91: one of the two ends a private reply early. */
    public void end(LocalDateTime now) {
        if (kind != ConversationKind.REPLY) throw new IllegalStateException("only a private reply can be ended");
        if (endedAt != null) return;
        LocalDateTime at = now.truncatedTo(ChronoUnit.MICROS);
        this.endedAt = at.isBefore(createdAt) ? createdAt : at;
    }

    public Long getId() {
        return id;
    }

    public String getPublicId() {
        return publicId;
    }

    public Long getMomentId() {
        return momentId;
    }

    public ConversationKind getKind() {
        return kind;
    }

    public Long getReplierId() {
        return replierId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getClosesAt() {
        return closesAt;
    }

    public LocalDateTime getEndedAt() {
        return endedAt;
    }
}
