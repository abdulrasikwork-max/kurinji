package com.safesphere.backend.safetycontact.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * N7 (D36 = N-128, V38 "circle_link_mutes"): the member chose "Don't ask again" for one owner. Later contacts of that owner
 * with the member's number make no request (and still tell the owner nothing).
 */
@Entity
@Table(name = "circle_link_mutes")
public class CircleLinkMute {

    @Embeddable
    public static class Key implements Serializable {

        @Column(name = "member_user_id", nullable = false)
        private Long memberUserId;

        @Column(name = "owner_user_id", nullable = false)
        private Long ownerUserId;

        protected Key() {
        }

        public Key(long memberUserId, long ownerUserId) {
            this.memberUserId = memberUserId;
            this.ownerUserId = ownerUserId;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(memberUserId, k.memberUserId)
                    && Objects.equals(ownerUserId, k.ownerUserId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(memberUserId, ownerUserId);
        }
    }

    @EmbeddedId
    private Key key;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** Required by JPA. */
    protected CircleLinkMute() {
    }

    public CircleLinkMute(long memberUserId, long ownerUserId, LocalDateTime now) {
        this.key = new Key(memberUserId, ownerUserId);
        this.createdAt = now;
    }

    public Key getKey() {
        return key;
    }
}
