package com.safesphere.backend.moderation.entity;

import com.safesphere.backend.moderation.entity.ModerationEnums.AppealStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A person's appeal against a restriction (Phase 7, V13): one per decision, decided by a moderator other than the one
 * who restricted. No free text: the appeal itself is the request "please look again".
 */
@Entity
@Table(name = "moderation_appeals")
public class ModerationAppeal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, updatable = false, length = 36, unique = true)
    private String publicId;

    @Column(name = "action_id", nullable = false, updatable = false, unique = true)
    private Long actionId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AppealStatus status = AppealStatus.OPEN;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "decided_by")
    private Long decidedBy;

    /** Two moderators deciding the same appeal at once: the second one gets 409. */
    @Version
    @Column(nullable = false)
    private long version;

    /** Required by JPA. */
    protected ModerationAppeal() {
    }

    public ModerationAppeal(long actionId, long userId, LocalDateTime now) {
        this.publicId = UUID.randomUUID().toString();
        this.actionId = actionId;
        this.userId = userId;
        this.createdAt = now;
    }

    public void decide(AppealStatus outcome, long moderatorId, LocalDateTime now) {
        this.status = outcome;
        this.decidedBy = moderatorId;
        this.decidedAt = now;
    }

    public Long getId() {
        return id;
    }

    public String getPublicId() {
        return publicId;
    }

    public Long getActionId() {
        return actionId;
    }

    public Long getUserId() {
        return userId;
    }

    public AppealStatus getStatus() {
        return status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getDecidedAt() {
        return decidedAt;
    }

    public Long getDecidedBy() {
        return decidedBy;
    }
}
