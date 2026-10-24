package com.safesphere.backend.social.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * Phase 13D (V23, Q7): the text a post had before an edit made while a review case about it was open. Read only by the
 * moderator of that case; deleted when the case closes, or with the post. Without an open case no history is kept.
 */
@Entity
@Table(name = "post_revisions")
public class PostRevision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "post_id", nullable = false, updatable = false)
    private Long postId;

    @Column(nullable = false, updatable = false, columnDefinition = "TEXT")
    private String body;

    @Column(updatable = false, length = 300)
    private String tip;

    @Column(name = "replaced_at", nullable = false, updatable = false)
    private LocalDateTime replacedAt;

    /** Required by JPA. */
    protected PostRevision() {
    }

    public PostRevision(long postId, String body, String tip, LocalDateTime replacedAt) {
        this.postId = postId;
        this.body = body;
        this.tip = tip;
        this.replacedAt = replacedAt.truncatedTo(ChronoUnit.MICROS);
    }

    public Long getId() {
        return id;
    }

    public Long getPostId() {
        return postId;
    }

    public String getBody() {
        return body;
    }

    public String getTip() {
        return tip;
    }

    public LocalDateTime getReplacedAt() {
        return replacedAt;
    }
}
