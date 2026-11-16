package com.safesphere.backend.community.entity;

import com.safesphere.backend.community.entity.CommunityEnums.BanReason;
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
 * Phase N3 (V34, N-4): a ban from a community on a roll number and/or a phone number, both only as HMACs, so a ban
 * survives the person's account deletion without keeping a readable number. A ban removes community access only; it
 * never touches the account, sign-in or any safety feature (CLAUDE.md §1, §11).
 */
@Entity
@Table(name = "bans")
public class Ban {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "community_id", nullable = false, updatable = false)
    private Long communityId;

    @Column(name = "roll_key", length = 64, updatable = false)
    private String rollKey;

    @Column(name = "phone_key", length = 64, updatable = false)
    private String phoneKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private BanReason reason;

    @Column(length = 200, updatable = false)
    private String note;

    @Column(name = "banned_by", nullable = false, updatable = false)
    private Long bannedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "lifted_by")
    private Long liftedBy;

    @Column(name = "lifted_at")
    private LocalDateTime liftedAt;

    protected Ban() {
    }

    public Ban(long communityId, String rollKey, String phoneKey, BanReason reason, String note, long bannedBy,
               LocalDateTime now) {
        if (rollKey == null && phoneKey == null) throw new IllegalArgumentException("A ban needs a key");
        this.communityId = communityId;
        this.rollKey = rollKey;
        this.phoneKey = phoneKey;
        this.reason = reason;
        this.note = note;
        this.bannedBy = bannedBy;
        this.createdAt = now.truncatedTo(ChronoUnit.MICROS);
    }

    public void lift(long by, LocalDateTime now) {
        LocalDateTime at = now.truncatedTo(ChronoUnit.MICROS);
        this.liftedBy = by;
        this.liftedAt = at.isBefore(createdAt) ? createdAt : at;
    }

    public boolean isInForce() {
        return liftedAt == null;
    }

    public Long getId() {
        return id;
    }

    public Long getCommunityId() {
        return communityId;
    }

    public String getRollKey() {
        return rollKey;
    }

    public String getPhoneKey() {
        return phoneKey;
    }

    public BanReason getReason() {
        return reason;
    }

    public String getNote() {
        return note;
    }

    public Long getBannedBy() {
        return bannedBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getLiftedAt() {
        return liftedAt;
    }
}
