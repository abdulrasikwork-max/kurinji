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
 * Phase 13B (V21): one photo. The file (re-encoded JPEG, no metadata) is in MediaStorage under [storageKey]. Until it is
 * attached ([postId] null) only its owner can fetch it; the clean-up job deletes unattached photos after 24 h.
 */
@Entity
@Table(name = "post_media", uniqueConstraints = {
        @UniqueConstraint(name = "uk_post_media_public_id", columnNames = "public_id"),
        @UniqueConstraint(name = "uk_post_media_storage_key", columnNames = "storage_key"),
        @UniqueConstraint(name = "uk_post_media_position", columnNames = {"post_id", "sort_order"})})
public class PostMedia {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, updatable = false, length = 36)
    private String publicId;

    @Column(name = "owner_id", nullable = false, updatable = false)
    private Long ownerId;

    @Column(name = "post_id")
    private Long postId;

    @Column(name = "sort_order")
    private Integer sortOrder;

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
    protected PostMedia() {
    }

    public PostMedia(long ownerId, String storageKey, int width, int height, int bytes, String sha256,
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

    public void attach(long postId, int sortOrder) {
        this.postId = postId;
        this.sortOrder = sortOrder;
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

    public Long getPostId() {
        return postId;
    }

    public Integer getSortOrder() {
        return sortOrder;
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
