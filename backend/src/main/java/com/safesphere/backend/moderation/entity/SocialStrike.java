package com.safesphere.backend.moderation.entity;

import com.safesphere.backend.social.entity.PostEnums.ModerationReason;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * Phase 13D (V23, M10): one human-confirmed violation (removed content, or a decision about a person). The weight
 * never changes; its decay is computed when read (StrikePolicy). Seen only by moderators, as suggested next steps.
 */
@Entity
@Table(name = "social_strikes")
public class SocialStrike {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private ModerationReason reason;

    @Column(nullable = false, updatable = false)
    private int weight;

    @Column(nullable = false, updatable = false, length = 36, unique = true)
    private String decision;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** Required by JPA. */
    protected SocialStrike() {
    }

    public SocialStrike(long userId, ModerationReason reason, int weight, String decision, LocalDateTime now) {
        this.userId = userId;
        this.reason = reason;
        this.weight = weight;
        this.decision = decision;
        this.createdAt = now.truncatedTo(ChronoUnit.MICROS);
    }

    public Long getUserId() {
        return userId;
    }

    public ModerationReason getReason() {
        return reason;
    }

    public int getWeight() {
        return weight;
    }

    public String getDecision() {
        return decision;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
