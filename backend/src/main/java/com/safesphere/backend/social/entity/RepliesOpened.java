package com.safesphere.backend.social.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * Phase N5 (V36, N-93): when a person last opened Replies, for their own marker (a dot). Theirs alone: never per
 * message, never shown to anyone, not a read receipt.
 */
@Entity
@Table(name = "replies_opened")
public class RepliesOpened {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "opened_at", nullable = false)
    private LocalDateTime openedAt;

    /** Required by JPA. */
    protected RepliesOpened() {
    }

    public RepliesOpened(long userId, LocalDateTime now) {
        this.userId = userId;
        opened(now);
    }

    public void opened(LocalDateTime now) {
        this.openedAt = now.truncatedTo(ChronoUnit.MICROS);
    }

    public Long getUserId() {
        return userId;
    }

    public LocalDateTime getOpenedAt() {
        return openedAt;
    }
}
