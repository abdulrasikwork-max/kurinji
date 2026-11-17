package com.safesphere.backend.social.entity;

import com.safesphere.backend.social.entity.ResponseEnums.PlanState;
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
 * Phase N5 (V36): one person and one Who's in? (N-90). ASKED until the author adds them (IN) or removes them after adding
 * (REMOVED: they cannot ask again). [setAside] = the author's silent "Not this time": it only tidies the author's own
 * list; nothing changes for the person who asked. Withdrawing deletes the row. [publicId] is what the author acts on,
 * so no user id ever leaves the server.
 */
@Entity
@Table(name = "plan_people",
        uniqueConstraints = {@UniqueConstraint(name = "uk_plan_people", columnNames = {"moment_id", "user_id"}),
                @UniqueConstraint(name = "uk_plan_people_public_id", columnNames = "public_id")})
public class PlanPerson {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, updatable = false, length = 36)
    private String publicId;

    @Column(name = "moment_id", nullable = false, updatable = false)
    private Long momentId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 7)
    private PlanState state;

    @Column(name = "asked_at", nullable = false, updatable = false)
    private LocalDateTime askedAt;

    @Column(name = "answered_at")
    private LocalDateTime answeredAt;

    @Column(name = "set_aside", nullable = false)
    private boolean setAside;

    /** Required by JPA. */
    protected PlanPerson() {
    }

    public PlanPerson(long momentId, long userId, LocalDateTime now) {
        this.publicId = UUID.randomUUID().toString();
        this.momentId = momentId;
        this.userId = userId;
        this.state = PlanState.ASKED;
        this.askedAt = now.truncatedTo(ChronoUnit.MICROS);
    }

    /** The author adds them (from ASKED only). */
    public void add(LocalDateTime now) {
        if (state != PlanState.ASKED) throw new IllegalStateException("only an ask can be added");
        this.state = PlanState.IN;
        this.answeredAt = later(now);
        this.setAside = false;
    }

    /** The author removes them after adding them; they cannot ask again. */
    public void remove(LocalDateTime now) {
        if (state != PlanState.IN) throw new IllegalStateException("only someone in can be removed");
        this.state = PlanState.REMOVED;
        this.answeredAt = later(now);
    }

    public void setAside(boolean aside) {
        if (state != PlanState.ASKED) throw new IllegalStateException("only an ask can be set aside");
        this.setAside = aside;
    }

    private LocalDateTime later(LocalDateTime now) {
        LocalDateTime at = now.truncatedTo(ChronoUnit.MICROS);
        return at.isBefore(askedAt) ? askedAt : at;
    }

    public Long getId() {
        return id;
    }

    public String getPublicId() {
        return publicId;
    }

    public Long getMomentId() {
        return momentId;
    }

    public Long getUserId() {
        return userId;
    }

    public PlanState getState() {
        return state;
    }

    public LocalDateTime getAskedAt() {
        return askedAt;
    }

    public LocalDateTime getAnsweredAt() {
        return answeredAt;
    }

    public boolean isSetAside() {
        return setAside;
    }
}
