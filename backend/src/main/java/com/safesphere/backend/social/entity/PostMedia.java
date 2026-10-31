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
 * Phase 13B (V21): one photo. The file (re-encoded JPEG, no metadata) is in MediaStorage under [storageKey]. Until it is
 * attached ([postId] null) only its owner can fetch it; the clean-up job deletes unattached photos after 24 h.
 * Phase 17C (V27): or one video ([kind] VIDEO): the stripped MP4 under the same key, [durationMs], and its poster frame
 * ([posterBytes], null until it arrives; a video without its poster is never attached).
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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 5)
    private MediaKind kind;

    @Column(name = "storage_key", nullable = false, updatable = false, length = 36)
    private String storageKey;

    @Column(nullable = false, updatable = false)
    private int width;

    @Column(nullable = false, updatable = false)
    private int height;

    @Column(nullable = false, updatable = false)
    private int bytes;

    @Column(name = "duration_ms", updatable = false)
    private Integer durationMs;

    @Column(name = "poster_bytes")
    private Integer posterBytes;

    @Column(nullable = false, updatable = false, length = 64)
    private String sha256;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** Required by JPA. */
    protected PostMedia() {
    }

    /** A photo. */
    public PostMedia(long ownerId, String storageKey, int width, int height, int bytes, String sha256,
                     LocalDateTime createdAt) {
        this(MediaKind.PHOTO, ownerId, storageKey, width, height, bytes, null, sha256, createdAt);
    }

    /** Phase 17C: a video (its poster comes later, [setPoster]). */
    public static PostMedia video(long ownerId, String storageKey, int width, int height, int bytes, int durationMs,
                                  String sha256, LocalDateTime createdAt) {
        return new PostMedia(MediaKind.VIDEO, ownerId, storageKey, width, height, bytes, durationMs, sha256, createdAt);
    }

    private PostMedia(MediaKind kind, long ownerId, String storageKey, int width, int height, int bytes,
                      Integer durationMs, String sha256, LocalDateTime createdAt) {
        this.kind = kind;
        this.durationMs = durationMs;
        this.publicId = UUID.randomUUID().toString();
        this.ownerId = ownerId;
        this.storageKey = storageKey;
        this.width = width;
        this.height = height;
        this.bytes = bytes;
        this.sha256 = sha256;
        this.createdAt = createdAt.truncatedTo(ChronoUnit.MICROS);
    }

    /** Phase 17C: the video's poster frame was (re)written as "<storageKey>.jpg". */
    public void setPoster(int bytes) {
        if (kind != MediaKind.VIDEO) throw new IllegalStateException("Only a video has a poster");
        this.posterBytes = bytes;
    }

    public boolean isVideo() {
        return kind == MediaKind.VIDEO;
    }

    /** A photo always; a video once its poster is there. */
    public boolean isReady() {
        return kind == MediaKind.PHOTO || posterBytes != null;
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

    public MediaKind getKind() {
        return kind;
    }

    public Integer getDurationMs() {
        return durationMs;
    }

    public Integer getPosterBytes() {
        return posterBytes;
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
