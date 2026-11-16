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
 * Phase N4 (V35): the photo of a moment (at most one: uk_moment_media_moment). The file is in MediaStorage under the
 * random [storageKey]; a row without [momentId] is an upload not attached yet. N4-B1 has the row only, so that purge,
 * account deletion and the file clean-up are complete from the start; uploading and serving arrive in N4-B2.
 */
@Entity
@Table(name = "moment_media", uniqueConstraints = {
        @UniqueConstraint(name = "uk_moment_media_public_id", columnNames = "public_id"),
        @UniqueConstraint(name = "uk_moment_media_storage_key", columnNames = "storage_key"),
        @UniqueConstraint(name = "uk_moment_media_moment", columnNames = "moment_id")})
public class MomentMedia {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, updatable = false, length = 36)
    private String publicId;

    @Column(name = "owner_id", nullable = false, updatable = false)
    private Long ownerId;

    @Column(name = "moment_id")
    private Long momentId;

    @Column(name = "storage_key", nullable = false, updatable = false, length = 36)
    private String storageKey;

    @Column(nullable = false, updatable = false)
    private int width;

    @Column(nullable = false, updatable = false)
    private int height;

    @Column(nullable = false, updatable = false)
    private int bytes;

    @Column(nullable = false, updatable = false, length = 64)
    private String sha256;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** Required by JPA. */
    protected MomentMedia() {
    }

    public MomentMedia(long ownerId, String storageKey, int width, int height, int bytes, String sha256,
                       LocalDateTime createdAt) {
        this.publicId = UUID.randomUUID().toString();
        this.ownerId = ownerId;
        this.storageKey = storageKey;
        this.width = width;
        this.height = height;
        this.bytes = bytes;
        this.sha256 = sha256;
        this.createdAt = createdAt.truncatedTo(ChronoUnit.MICROS);
    }

    public void attach(long momentId) {
        if (this.momentId != null) throw new IllegalStateException("Already attached");
        this.momentId = momentId;
    }

    public Long getId() {
        return id;
    }

    public String getPublicId() {
        return publicId;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public Long getMomentId() {
        return momentId;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public int getBytes() {
        return bytes;
    }

    public String getSha256() {
        return sha256;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
