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

/**
 * Phase 17G-2 (U5, V30): "I went" - a private record that one person went to one place - with an optional 1-5 rating on the same
 * row (so a rating cannot exist without "I went"). Returned only to its owner. The only thing ever derived from these rows for
 * others is the guarded average of RatingPolicy; the owner's id never leaves the server. Self-declared: there is no proof.
 */
@Entity
@Table(name = "place_visits",
        uniqueConstraints = @UniqueConstraint(name = "uk_place_visits_user_place", columnNames = {"user_id", "place_id"}))
public class PlaceVisit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "place_id", nullable = false)
    private Long placeId;

    /** 1..5, or null when the person went but did not rate. */
    @Column(name = "rating")
    private Integer rating;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "rated_at")
    private LocalDateTime ratedAt;

    /** Required by JPA. */
    protected PlaceVisit() {
    }

    public PlaceVisit(long userId, long placeId, LocalDateTime now) {
        this.userId = userId;
        this.placeId = placeId;
        this.createdAt = now.truncatedTo(ChronoUnit.MICROS);
    }

    /** Sets the rating (the caller checked 1..5 with RatingPolicy.isValid). */
    public void rate(int value, LocalDateTime now) {
        this.rating = value;
        this.ratedAt = now.truncatedTo(ChronoUnit.MICROS);
    }

    public void clearRating() {
        this.rating = null;
        this.ratedAt = null;
    }

    /** A duplicate place was merged into [placeId]: this visit follows it. */
    public void moveTo(long placeId) {
        this.placeId = placeId;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getPlaceId() {
        return placeId;
    }

    public Integer getRating() {
        return rating;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getRatedAt() {
        return ratedAt;
    }
}
