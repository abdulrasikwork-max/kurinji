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
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * A SafeSphere account. Maps to the "users" table created by migration V1.
 * Phase N2: an account exists only for a verified phone number; no e-mail, no password (V33).
 * Timestamps are LocalDateTime values that always hold UTC.
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    private boolean enabled = true;

    /** Phase 7. Never set from a request body; only AdminService (ADMIN callers) changes it, MODERATOR at most. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserRole role = UserRole.USER;

    /**
     * Phase 10A (V16): the credentials stamp. Tokens carry it; a different stamp = a revoked token. Null = never changed.
     * N2: set when the person signs out their other phones (before N2: by a password change).
     */
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

    /** Phase 17B (V26): the account's mobile number, +91 and 10 digits; verified once [phoneVerifiedAt] is set. */
    @Column(name = "phone_e164", length = 16)
    private String phoneE164;

    /** Phase 17B (V26): when a code sent to [phoneE164] was entered. Null = this account has not verified a phone. */
    @Column(name = "phone_verified_at")
    private LocalDateTime phoneVerifiedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** Required by JPA. */
    protected User() {
    }

    /**
     * Phase N2: a new account, created only after a code sent to [phoneE164] was entered (sign-in and sign-up are one
     * flow): the number is verified from the first moment.
     */
    public User(String name, String phoneE164, LocalDateTime phoneVerifiedAt) {
        this.name = name;
        this.phoneE164 = phoneE164;
        this.phoneVerifiedAt = phoneVerifiedAt.truncatedTo(ChronoUnit.MICROS);
    }

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        this.createdAt = now;
        this.updatedAt = now;
        // V26 CHECK: verified never before created, even when the service clock is a little behind this one.
        if (phoneVerifiedAt != null && phoneVerifiedAt.isBefore(now)) this.phoneVerifiedAt = now;
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

    public String getPhoneE164() {
        return phoneE164;
    }

    public boolean isPhoneVerified() {
        return phoneVerifiedAt != null;
    }

    /** Phase 17B: a code sent to [phoneE164] was entered; the number becomes the account's verified number. */
    public void markPhoneVerified(String phoneE164, LocalDateTime now) {
        this.phoneE164 = phoneE164;
        // Never before created_at (V26 CHECK), even with a clock a little behind the one that stamped the row.
        LocalDateTime at = createdAt != null && now.isBefore(createdAt) ? createdAt : now;
        this.phoneVerifiedAt = at.truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * The credentials stamp tokens carry (claim "cca"): microseconds since the epoch of [credentialsChangedAt], or null
     * while it was never set. Exact equality is compared, so no clock or second-rounding question arises.
     */
    public Long credentialsStamp() {
        if (credentialsChangedAt == null) return null;
        java.time.Instant instant = credentialsChangedAt.toInstant(ZoneOffset.UTC);
        return instant.getEpochSecond() * 1_000_000L + instant.getNano() / 1_000L;
    }

    /** N2 (N-26): a new stamp; every token issued before is refused from now on (the caller gets a fresh one). */
    public void signOutEverywhereElse(LocalDateTime now) {
        this.credentialsChangedAt = now.truncatedTo(ChronoUnit.MICROS);
    }

    /** N2 (N-24): the person's real name, already checked by RealNamePolicy. */
    public void rename(String name) {
        this.name = name;
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
     * Turns the row into an anonymous, disabled tombstone (V16 CHECKs): no name, no number, no role, every token
     * refused. Used only when the moderation audit log still refers to this account.
     */
    public void erase(LocalDateTime now) {
        LocalDateTime at = now.truncatedTo(ChronoUnit.MICROS);
        this.name = "Deleted account";
        this.phoneE164 = null; // Phase 17B (V26 CHECK): a tombstone keeps no number
        this.phoneVerifiedAt = null;
        this.enabled = false;
        this.role = UserRole.USER;
        this.credentialsChangedAt = at;
        this.deletionRequestedAt = null;
        this.deletionDueAt = null;
        this.erasedAt = at;
    }
}
