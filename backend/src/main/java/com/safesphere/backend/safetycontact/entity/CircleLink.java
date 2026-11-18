package com.safesphere.backend.safetycontact.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * N7 (D36 = N-128, V38 "circle_links"): "Also in the IRL app". A contact of the owner whose number belongs to a verified IRL
 * account (the member). PENDING until the member accepts; nothing about it reaches the owner before ACCEPTED. The member
 * and the API only ever see [publicId], never a row id.
 */
@Entity
@Table(name = "circle_links")
public class CircleLink {

    public enum Status { PENDING, ACCEPTED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, updatable = false, unique = true, length = 36)
    private String publicId;

    @Column(name = "contact_id", nullable = false, updatable = false, unique = true)
    private Long contactId;

    @Column(name = "owner_user_id", nullable = false, updatable = false)
    private Long ownerUserId;

    @Column(name = "member_user_id", nullable = false, updatable = false)
    private Long memberUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private Status status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    /** Required by JPA. */
    protected CircleLink() {
    }

    public CircleLink(long contactId, long ownerUserId, long memberUserId, LocalDateTime now) {
        if (ownerUserId == memberUserId) throw new IllegalArgumentException("nobody is in their own Safety Circle");
        this.publicId = UUID.randomUUID().toString();
        this.contactId = contactId;
        this.ownerUserId = ownerUserId;
        this.memberUserId = memberUserId;
        this.status = Status.PENDING;
        this.createdAt = now;
    }

    public void accept(LocalDateTime now) {
        this.status = Status.ACCEPTED;
        this.decidedAt = now;
    }

    public Long getId() {
        return id;
    }

    public String getPublicId() {
        return publicId;
    }

    public Long getContactId() {
        return contactId;
    }

    public Long getOwnerUserId() {
        return ownerUserId;
    }

    public Long getMemberUserId() {
        return memberUserId;
    }

    public Status getStatus() {
        return status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getDecidedAt() {
        return decidedAt;
    }
}
