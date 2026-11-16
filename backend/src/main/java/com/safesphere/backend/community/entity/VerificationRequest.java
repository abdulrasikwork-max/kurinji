package com.safesphere.backend.community.entity;

import com.safesphere.backend.community.entity.CommunityEnums.Method;
import com.safesphere.backend.community.entity.CommunityEnums.Programme;
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
 * Phase N3 (V34): one open verification request per person, deleted at the decision, withdrawal or expiry.
 * IN_PERSON: only the HMAC of the short code the applicant shows, and its expiry; a verifier's lookup "claims" it.
 * ID_PHOTO: the random name of the encrypted image file (never the image itself).
 * The roll number the applicant typed is only its HMAC.
 */
@Entity
@Table(name = "verification_requests", uniqueConstraints = {
        @UniqueConstraint(name = "uk_verification_requests_public", columnNames = "public_id"),
        @UniqueConstraint(name = "uk_verification_requests_user", columnNames = "user_id"),
        @UniqueConstraint(name = "uk_verification_requests_code", columnNames = "code_hash"),
        @UniqueConstraint(name = "uk_verification_requests_photo", columnNames = "photo_key")})
public class VerificationRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, updatable = false, length = 36)
    private String publicId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "community_id", nullable = false, updatable = false)
    private Long communityId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10, updatable = false)
    private Method method;

    @Column(name = "roll_key", nullable = false, length = 64, updatable = false)
    private String rollKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12, updatable = false)
    private Programme programme;

    @Column(name = "graduation_year", nullable = false, updatable = false)
    private int graduationYear;

    @Column(name = "code_hash", length = 64)
    private String codeHash;

    @Column(name = "code_expires_at")
    private LocalDateTime codeExpiresAt;

    @Column(name = "photo_key", length = 36)
    private String photoKey;

    @Column(name = "photo_uploaded_at")
    private LocalDateTime photoUploadedAt;

    @Column(name = "claimed_by")
    private Long claimedBy;

    @Column(name = "claimed_at")
    private LocalDateTime claimedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    protected VerificationRequest() {
    }

    public VerificationRequest(long userId, long communityId, Method method, String rollKey, Programme programme,
                               int graduationYear, LocalDateTime now, LocalDateTime expiresAt) {
        this.publicId = UUID.randomUUID().toString();
        this.userId = userId;
        this.communityId = communityId;
        this.method = method;
        this.rollKey = rollKey;
        this.programme = programme;
        this.graduationYear = graduationYear;
        this.createdAt = now.truncatedTo(ChronoUnit.MICROS);
        this.expiresAt = expiresAt.truncatedTo(ChronoUnit.MICROS);
    }

    /** IN_PERSON: a new code (the old one dies) and no claim (a new showing of the code starts afresh). */
    public void newCode(String codeHash, LocalDateTime expiresAt) {
        if (method != Method.IN_PERSON) throw new IllegalStateException("Only in-person requests have a code");
        this.codeHash = codeHash;
        this.codeExpiresAt = expiresAt.truncatedTo(ChronoUnit.MICROS);
        this.claimedBy = null;
        this.claimedAt = null;
    }

    public boolean hasLiveCode(LocalDateTime now) {
        return codeHash != null && codeExpiresAt.isAfter(now);
    }

    /** ID_PHOTO: the encrypted file's name (replacing any earlier one, which the caller deletes after commit). */
    public void photo(String photoKey, LocalDateTime now) {
        if (method != Method.ID_PHOTO) throw new IllegalStateException("Only photo requests have a photo");
        this.photoKey = photoKey;
        this.photoUploadedAt = now.truncatedTo(ChronoUnit.MICROS);
    }

    /** A verifier (or admin) typed the right code: only they may decide it until the claim ends. */
    public void claim(long deciderId, LocalDateTime now) {
        this.claimedBy = deciderId;
        this.claimedAt = now.truncatedTo(ChronoUnit.MICROS);
    }

    public boolean claimedBy(long deciderId, LocalDateTime notBefore) {
        return claimedBy != null && claimedBy == deciderId && claimedAt != null && claimedAt.isAfter(notBefore);
    }

    public boolean isExpired(LocalDateTime now) {
        return !expiresAt.isAfter(now);
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

    public Long getCommunityId() {
        return communityId;
    }

    public Method getMethod() {
        return method;
    }

    public String getRollKey() {
        return rollKey;
    }

    public Programme getProgramme() {
        return programme;
    }

    public int getGraduationYear() {
        return graduationYear;
    }

    public LocalDateTime getCodeExpiresAt() {
        return codeExpiresAt;
    }

    public String getPhotoKey() {
        return photoKey;
    }

    public LocalDateTime getPhotoUploadedAt() {
        return photoUploadedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }
}
