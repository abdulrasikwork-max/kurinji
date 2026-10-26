package com.safesphere.backend.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * A SafeSphere account. Maps to the "users" table created by migration V1.
 * Only the BCrypt hash of the password is stored, never the password itself.
 * Timestamps are LocalDateTime values that always hold UTC.
 */
@Entity
@Table(
        name = "users",
        uniqueConstraints = @UniqueConstraint(name = "uk_users_email", columnNames = "email")
)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 255)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(nullable = false)
    private boolean enabled = true;

    /** Phase 7. Never set from a request body; only AdminService (ADMIN callers) changes it, MODERATOR at most. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserRole role = UserRole.USER;

    /** Phase 10A (V16): the credentials stamp. Tokens carry it; a different stamp = a revoked token. Null = never changed. */
    @Column(name = "credentials_changed_at")
    private LocalDateTime credentialsChangedAt;

    /** Phase 10A (V16): a pending deletion request (both set or both null). */
    @Column(name = "deletion_requested_at")
    private LocalDateTime deletionRequestedAt;

    @Column(name = "deletion_due_at")
    private LocalDateTime deletionDueAt;

    /** Phase 10A (V16): set only on an anonymised tombstone (an account the moderation audit log still refers to). */
    @Column(name = "erased_at")
    private LocalDateTime erasedAt;

    /** Phase 13A (V20): when the person declared an adult date of birth. The date itself is never stored. */
    @Column(name = "age_confirmed_at")
    private LocalDateTime ageConfirmedAt;

    /**
     * V25: when the person proved they control [email] (opened the verification link). Null = not verified: such an
     * account gets no session at all. Separate from [enabled], a pending deletion, moderation standing and sessions.
     */
    @Column(name = "email_verified_at")
    private LocalDateTime emailVerifiedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** Required by JPA. */
    protected User() {
    }

    public User(String name, String email, String passwordHash) {
        this.name = name;
        this.email = email;
        this.passwordHash = passwordHash;
    }

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public UserRole getRole() {
        return role;
    }

    public void setRole(UserRole role) {
        this.role = role;
    }

    public LocalDateTime getCredentialsChangedAt() {
        return credentialsChangedAt;
    }

    public LocalDateTime getDeletionRequestedAt() {
        return deletionRequestedAt;
    }

    public LocalDateTime getDeletionDueAt() {
        return deletionDueAt;
    }

    public LocalDateTime getErasedAt() {
        return erasedAt;
    }

    public boolean isErased() {
        return erasedAt != null;
    }

    public LocalDateTime getAgeConfirmedAt() {
        return ageConfirmedAt;
    }

    public boolean isAgeConfirmed() {
        return ageConfirmedAt != null;
    }

    /** Phase 13A: records that an adult date of birth was declared (checked by AgePolicy, then discarded). Kept once set. */
    public void confirmAge(LocalDateTime now) {
        if (ageConfirmedAt == null) this.ageConfirmedAt = now.truncatedTo(ChronoUnit.MICROS);
    }

    public LocalDateTime getEmailVerifiedAt() {
        return emailVerifiedAt;
    }

    public boolean isEmailVerified() {
        return emailVerifiedAt != null;
    }

    /** V25: the owner of [email] opened a valid verification link. Kept once set. */
    public void markEmailVerified(LocalDateTime now) {
        if (emailVerifiedAt != null) return;
        // Never before created_at (V25 CHECK), even with a clock a little behind the one that stamped the row.
        LocalDateTime at = createdAt != null && now.isBefore(createdAt) ? createdAt : now;
        this.emailVerifiedAt = at.truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * The credentials stamp tokens carry (claim "cca"): microseconds since the epoch of [credentialsChangedAt], or null
     * while the password was never changed. Exact equality is compared, so no clock or second-rounding question arises.
     */
    public Long credentialsStamp() {
        if (credentialsChangedAt == null) return null;
        java.time.Instant instant = credentialsChangedAt.toInstant(ZoneOffset.UTC);
        return instant.getEpochSecond() * 1_000_000L + instant.getNano() / 1_000L;
    }

    /** Phase 10A: a new password hash and a new stamp; every token issued before is refused from now on. */
    public void changePassword(String newPasswordHash, LocalDateTime now) {
        this.passwordHash = newPasswordHash;
        this.credentialsChangedAt = now.truncatedTo(ChronoUnit.MICROS);
    }

    public void requestDeletion(LocalDateTime now, LocalDateTime dueAt) {
        this.deletionRequestedAt = now.truncatedTo(ChronoUnit.MICROS);
        this.deletionDueAt = dueAt.truncatedTo(ChronoUnit.MICROS);
    }

    /** Moves a pending deletion later (an emergency happened during the grace period). Never earlier. */
    public void postponeDeletion(LocalDateTime dueAt) {
        if (deletionDueAt != null && dueAt.isAfter(deletionDueAt)) {
            this.deletionDueAt = dueAt.truncatedTo(ChronoUnit.MICROS);
        }
    }

    public void cancelDeletion() {
        this.deletionRequestedAt = null;
        this.deletionDueAt = null;
    }

    /**
     * Turns the row into an anonymous, disabled tombstone (V16 CHECKs): no name, no e-mail, no usable password, no role,
     * every token refused. Used only when the moderation audit log still refers to this account.
     */
    public void erase(String unusablePasswordHash, LocalDateTime now) {
        LocalDateTime at = now.truncatedTo(ChronoUnit.MICROS);
        this.name = "Deleted account";
        this.email = "erased-" + id + "@erased.invalid";
        this.passwordHash = unusablePasswordHash;
        this.enabled = false;
        this.role = UserRole.USER;
        this.credentialsChangedAt = at;
        this.deletionRequestedAt = null;
        this.deletionDueAt = null;
        this.erasedAt = at;
    }
}
