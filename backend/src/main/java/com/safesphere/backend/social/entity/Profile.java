package com.safesphere.backend.social.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Phase 13A (V20): the social face of one account. The handle is lower-case ASCII; the display name is the person's
 * choice (the account name stays the real name). Languages and interests are closed code lists stored comma-separated.
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

    @Column(name = "display_name", nullable = false, length = 40)
    private String displayName;

    @Column(length = 160)
    private String bio;

    @Column(length = 40)
    private String languages;

    @Column(length = 200)
    private String interests;

    @Column(length = 60)
    private String city;

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

    /** Sets the editable fields (already checked by the policies). Null bio/city/lists = none. */
    public void edit(String displayName, String bio, String languages, String interests, String city, LocalDateTime now) {
        this.displayName = displayName;
        this.bio = bio;
        this.languages = languages;
        this.interests = interests;
        this.city = city;
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

    public String getLanguages() {
        return languages;
    }

    public String getInterests() {
        return interests;
    }

    public String getCity() {
        return city;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
