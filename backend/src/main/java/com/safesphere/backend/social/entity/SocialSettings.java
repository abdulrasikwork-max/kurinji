package com.safesphere.backend.social.entity;

import com.safesphere.backend.social.entity.ResponseEnums.PrivateReplies;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/** Phase N5 (V36, N-91): who may reply privately to a person's moments. No row = MY_PEOPLE (the default). */
@Entity
@Table(name = "social_settings")
public class SocialSettings {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "private_replies", nullable = false, length = 9)
    private PrivateReplies privateReplies;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** Required by JPA. */
    protected SocialSettings() {
    }

    public SocialSettings(long userId, PrivateReplies value, LocalDateTime now) {
        this.userId = userId;
        privateReplies(value, now);
    }

    public void privateReplies(PrivateReplies value, LocalDateTime now) {
        this.privateReplies = value;
        this.updatedAt = now.truncatedTo(ChronoUnit.MICROS);
    }

    public Long getUserId() {
        return userId;
    }

    public PrivateReplies getPrivateReplies() {
        return privateReplies;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
