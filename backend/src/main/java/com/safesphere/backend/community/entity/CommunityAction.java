package com.safesphere.backend.community.entity;

import com.safesphere.backend.community.entity.CommunityEnums.ActionKind;
import com.safesphere.backend.community.entity.CommunityEnums.Method;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * Phase N3 (V34): the community log, append-only. [actorId] null = the system (the graduation job). [subjectUserId] is
 * the person concerned (gone with their account). [reason] is a RefusalReason, BanReason or EndReason name. [detail]
 * only for NAME_CORRECTED: the spelling before the correction (shown to the person).
 */
@Entity
@Table(name = "community_actions")
public class CommunityAction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "community_id", nullable = false, updatable = false)
    private Long communityId;

    @Column(name = "actor_id", updatable = false)
    private Long actorId;

    @Column(name = "subject_user_id", updatable = false)
    private Long subjectUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private ActionKind action;

    @Enumerated(EnumType.STRING)
    @Column(length = 10, updatable = false)
    private Method method;

    @Column(length = 20, updatable = false)
    private String reason;

    @Column(length = 100, updatable = false)
    private String detail;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected CommunityAction() {
    }

    public CommunityAction(long communityId, Long actorId, Long subjectUserId, ActionKind action, Method method,
                           String reason, String detail, LocalDateTime now) {
        if ((action == ActionKind.NAME_CORRECTED) != (detail != null)) {
            throw new IllegalArgumentException("detail only for NAME_CORRECTED");
        }
        this.communityId = communityId;
        this.actorId = actorId;
        this.subjectUserId = subjectUserId;
        this.action = action;
        this.method = method;
        this.reason = reason;
        this.detail = detail;
        this.createdAt = now.truncatedTo(ChronoUnit.MICROS);
    }

    public Long getId() {
        return id;
    }

    public Long getCommunityId() {
        return communityId;
    }

    public Long getActorId() {
        return actorId;
    }

    public Long getSubjectUserId() {
        return subjectUserId;
    }

    public ActionKind getAction() {
        return action;
    }

    public Method getMethod() {
        return method;
    }

    public String getReason() {
        return reason;
    }

    public String getDetail() {
        return detail;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
