package com.safesphere.backend.social.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Formula;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Phase 13A (V20): the social face of one account. The handle is lower-case ASCII; the display name is the person's
 * choice (the account name stays the real name).
 * Holds no counts of anything.
 */
@Entity
@Table(name = "profiles", uniqueConstraints = {
        @UniqueConstraint(name = "uk_profiles_public_id", columnNames = "public_id"),
        @UniqueConstraint(name = "uk_profiles_user", columnNames = "user_id"),
        @UniqueConstraint(name = "uk_profiles_handle", columnNames = "handle"),
        @UniqueConstraint(name = "uk_profiles_previous_handle", columnNames = "previous_handle")})
public class Profile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, updatable = false, length = 36)
    private String publicId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(nullable = false, length = 20)
    private String handle;

    @Column(name = "previous_handle", length = 20)
    private String previousHandle;

    /** UTC; set together with [previousHandle]. */
    @Column(name = "previous_handle_until")
    private LocalDateTime previousHandleUntil;

    /** UTC; null until the handle was changed once. */
    @Column(name = "handle_changed_at")
    private LocalDateTime handleChangedAt;

    /**
     * Phase N2 (N-23, V33): the profile has no name of its own any more; it shows the account's real name (users.name),
     * read with the row (no extra query, never written from here).
     */
    @Formula("(SELECT u.name FROM users u WHERE u.id = user_id)")
    private String displayName;

    @Column(length = 160)
    private String bio;

    /** Phase 17E (V28): the storage key of the profile photo (a new random key per upload); null = no photo. */
    @Column(name = "photo_key", length = 36)
    private String photoKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** Required by JPA. */
    protected Profile() {
    }

    public Profile(long userId, String handle, LocalDateTime now) {
        this.publicId = UUID.randomUUID().toString();
        this.userId = userId;
        this.handle = handle;
        LocalDateTime at = now.truncatedTo(ChronoUnit.MICROS);
        this.createdAt = at;
        this.updatedAt = at;
    }

    /** Sets the editable field (already checked by the policy). Null bio = none. */
    public void edit(String bio, LocalDateTime now) {
        this.bio = bio;
        this.updatedAt = now.truncatedTo(ChronoUnit.MICROS);
    }

    /** A new handle; the old one stays reserved until [reservedUntil]. The caller checked the 30-day rule. */
    public void changeHandle(String newHandle, LocalDateTime now, LocalDateTime reservedUntil) {
        LocalDateTime at = now.truncatedTo(ChronoUnit.MICROS);
        this.previousHandle = this.handle;
        this.previousHandleUntil = reservedUntil.truncatedTo(ChronoUnit.MICROS);
        this.handle = newHandle;
        this.handleChangedAt = at;
        this.updatedAt = at;
    }

    /** Phase 17E: a new photo (a fresh key), or none (null). Returns the key it replaced, whose file must go. */
    public String replacePhoto(String key, LocalDateTime now) {
        String old = this.photoKey;
        this.photoKey = key;
        this.updatedAt = now.truncatedTo(ChronoUnit.MICROS);
        return old;
    }

    public String getPhotoKey() {
        return photoKey;
    }

    /** Ends a reservation whose time is over (so the handle can be taken again). */
    public void releasePreviousHandle() {
        this.previousHandle = null;
        this.previousHandleUntil = null;
    }

    public Long getId() {
        return id;
    }

    public String getPublicId() {
        return publicId;
    }

    public Long getUserId() {
        return userId;
    }

    public String getHandle() {
        return handle;
    }

    public String getPreviousHandle() {
        return previousHandle;
    }

    public LocalDateTime getPreviousHandleUntil() {
        return previousHandleUntil;
    }

    public LocalDateTime getHandleChangedAt() {
        return handleChangedAt;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getBio() {
        return bio;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
