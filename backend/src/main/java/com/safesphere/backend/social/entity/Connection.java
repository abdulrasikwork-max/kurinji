package com.safesphere.backend.social.entity;

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
 * Phase 13A (V20): the one row for a pair of people, whatever the direction of the request. [userLow] < [userHigh]
 * always (the constructor orders them; MySQL cannot CHECK it, see V20), so the unique pair index allows one row per pair
 * even when both people ask at the same moment.
 */
@Entity
@Table(name = "connections", uniqueConstraints = {
        @UniqueConstraint(name = "uk_connections_public_id", columnNames = "public_id"),
        @UniqueConstraint(name = "uk_connections_pair", columnNames = {"user_low", "user_high"})})
public class Connection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, updatable = false, length = 36)
    private String publicId;

    @Column(name = "user_low", nullable = false, updatable = false)
    private Long userLow;

    @Column(name = "user_high", nullable = false, updatable = false)
    private Long userHigh;

    @Column(name = "requester_id", nullable = false, updatable = false)
    private Long requesterId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ConnectionStatus status;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private LocalDateTime requestedAt;

    @Column(name = "responded_at")
    private LocalDateTime respondedAt;

    /** Required by JPA. */
    protected Connection() {
    }

    private Connection(long requesterId, long otherId, ConnectionStatus status, LocalDateTime now) {
        if (requesterId == otherId) throw new IllegalArgumentException("A connection needs two different people");
        LocalDateTime at = now.truncatedTo(ChronoUnit.MICROS);
        this.publicId = UUID.randomUUID().toString();
        this.userLow = Math.min(requesterId, otherId);
        this.userHigh = Math.max(requesterId, otherId);
        this.requesterId = requesterId;
        this.status = status;
        this.requestedAt = at;
        this.respondedAt = status == ConnectionStatus.PENDING ? null : at;
    }

    /** A request from [requesterId] to [addresseeId], waiting for an answer. */
    public static Connection request(long requesterId, long addresseeId, LocalDateTime now) {
        return new Connection(requesterId, addresseeId, ConnectionStatus.PENDING, now);
    }

    /** Connected at once (an invite code redeemed by [redeemerId]; the inviter agreed by sharing it). */
    public static Connection fromInvite(long inviterId, long redeemerId, LocalDateTime now) {
        return new Connection(inviterId, redeemerId, ConnectionStatus.ACCEPTED, now);
    }

    public void accept(LocalDateTime now) {
        this.status = ConnectionStatus.ACCEPTED;
        this.respondedAt = later(now);
    }

    public void decline(LocalDateTime now) {
        this.status = ConnectionStatus.DECLINED;
        this.respondedAt = later(now);
    }

    private LocalDateTime later(LocalDateTime now) {
        LocalDateTime at = now.truncatedTo(ChronoUnit.MICROS);
        return at.isBefore(requestedAt) ? requestedAt : at;
    }

    public boolean involves(long userId) {
        return userLow == userId || userHigh == userId;
    }

    /** The other person of the pair, seen from [userId] (who must be one of them). */
    public long otherThan(long userId) {
        if (!involves(userId)) throw new IllegalArgumentException("Not part of this connection");
        return userLow == userId ? userHigh : userLow;
    }

    public long addresseeId() {
        return otherThan(requesterId);
    }

    public Long getId() {
        return id;
    }

    public String getPublicId() {
        return publicId;
    }

    public Long getUserLow() {
        return userLow;
    }

    public Long getUserHigh() {
        return userHigh;
    }

    public Long getRequesterId() {
        return requesterId;
    }

    public ConnectionStatus getStatus() {
        return status;
    }

    public LocalDateTime getRequestedAt() {
        return requestedAt;
    }

    public LocalDateTime getRespondedAt() {
        return respondedAt;
    }
}
