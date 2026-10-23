package com.safesphere.backend.social.entity;

import com.safesphere.backend.social.entity.PostEnums.CommentKind;
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
 * Phase 13B (V21): a Perspective on a post. One level of replies ([parentId]). The post's author can hide it (its writer
 * still sees it, marked "Hidden by the author", Q5); on an Ask the asker can mark an ANSWER "This helped".
 */
@Entity
@Table(name = "comments", uniqueConstraints = @UniqueConstraint(name = "uk_comments_public_id", columnNames = "public_id"))
public class Comment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, updatable = false, length = 36)
    private String publicId;

    @Column(name = "post_id", nullable = false, updatable = false)
    private Long postId;

    @Column(name = "author_id", nullable = false, updatable = false)
    private Long authorId;

    @Column(name = "parent_id", updatable = false)
    private Long parentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 9)
    private CommentKind kind;

    @Column(nullable = false, updatable = false, length = 1000)
    private String body;

    @Column(name = "hidden_by_author_at")
    private LocalDateTime hiddenByAuthorAt;

    @Column(name = "helped_at")
    private LocalDateTime helpedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** Required by JPA. */
    protected Comment() {
    }

    public Comment(long postId, long authorId, Long parentId, CommentKind kind, String body, LocalDateTime createdAt) {
        this.publicId = UUID.randomUUID().toString();
        this.postId = postId;
        this.authorId = authorId;
        this.parentId = parentId;
        this.kind = kind;
        this.body = body;
        this.createdAt = createdAt.truncatedTo(ChronoUnit.MICROS);
    }

    public void hide(LocalDateTime now) {
        if (hiddenByAuthorAt == null) hiddenByAuthorAt = now.truncatedTo(ChronoUnit.MICROS);
    }

    public void unhide() {
        hiddenByAuthorAt = null;
    }

    public void markHelped(LocalDateTime now) {
        if (helpedAt == null) helpedAt = now.truncatedTo(ChronoUnit.MICROS);
    }

    public void unmarkHelped() {
        helpedAt = null;
    }

    public boolean isHidden() {
        return hiddenByAuthorAt != null;
    }

    public boolean isHelped() {
        return helpedAt != null;
    }

    public Long getId() {
        return id;
    }

    public String getPublicId() {
        return publicId;
    }

    public Long getPostId() {
        return postId;
    }

    public Long getAuthorId() {
        return authorId;
    }

    public Long getParentId() {
        return parentId;
    }

    public CommentKind getKind() {
        return kind;
    }

    public String getBody() {
        return body;
    }

    public LocalDateTime getHiddenByAuthorAt() {
        return hiddenByAuthorAt;
    }

    public LocalDateTime getHelpedAt() {
        return helpedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
