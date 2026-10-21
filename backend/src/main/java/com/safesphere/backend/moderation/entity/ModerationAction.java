package com.safesphere.backend.moderation.entity;

import com.safesphere.backend.community.entity.SignalCategory;
import com.safesphere.backend.moderation.entity.ModerationEnums.ActionReason;
import com.safesphere.backend.moderation.entity.ModerationEnums.ActionType;
import com.safesphere.backend.user.entity.UserRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One entry of the moderation audit log (Phase 7, V13). Append-only: every column is insert-only, and the application
 * has no update or delete path except the retention clean-up. Who acted ([actorId]), what, why (a code), on which case,
 * and on whom ([subjectUserId], only for decisions about a person). Readable by ADMIN only.
 */
@Entity
@Table(name = "moderation_actions")
public class ModerationAction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, updatable = false, length = 36, unique = true)
    private String publicId;

    @Column(name = "actor_id", nullable = false, updatable = false)
    private Long actorId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 30)
    private ActionType action;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 30)
    private ActionReason reason;

    @Column(name = "case_ref", updatable = false, length = 36)
    private String caseRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "signal_category", updatable = false, length = 30)
    private SignalCategory signalCategory;

    @Column(name = "subject_user_id", updatable = false)
    private Long subjectUserId;

    @Column(name = "restricted_until", updatable = false)
    private LocalDateTime restrictedUntil;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_role", updatable = false, length = 20)
    private UserRole newRole;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private LocalDateTime occurredAt;

    /** Required by JPA. */
    protected ModerationAction() {
    }

    private ModerationAction(long actorId, ActionType action, ActionReason reason, LocalDateTime now) {
        this.publicId = UUID.randomUUID().toString();
        this.actorId = actorId;
        this.action = action;
        this.reason = reason;
        this.occurredAt = now;
    }

    public static ModerationAction onCase(long actorId, ActionType action, ActionReason reason, String caseRef,
                                          SignalCategory category, LocalDateTime now) {
        ModerationAction entry = new ModerationAction(actorId, action, reason, now);
        entry.caseRef = caseRef;
        entry.signalCategory = category;
        return entry;
    }

    public static ModerationAction restriction(long actorId, ActionReason reason, String caseRef, SignalCategory category,
                                               long subjectUserId, LocalDateTime until, LocalDateTime now) {
        ModerationAction entry = onCase(actorId, ActionType.RESTRICT, reason, caseRef, category, now);
        entry.subjectUserId = subjectUserId;
        entry.restrictedUntil = until;
        return entry;
    }

    public static ModerationAction aboutPerson(long actorId, ActionType action, ActionReason reason, long subjectUserId,
                                               UserRole newRole, LocalDateTime now) {
        ModerationAction entry = new ModerationAction(actorId, action, reason, now);
        entry.subjectUserId = subjectUserId;
        entry.newRole = newRole;
        return entry;
    }

    public Long getId() {
        return id;
    }

    public String getPublicId() {
        return publicId;
    }

    public Long getActorId() {
        return actorId;
    }

    public ActionType getAction() {
        return action;
    }

    public ActionReason getReason() {
        return reason;
    }

    public String getCaseRef() {
        return caseRef;
    }

    public SignalCategory getSignalCategory() {
        return signalCategory;
    }

    public Long getSubjectUserId() {
        return subjectUserId;
    }

    public LocalDateTime getRestrictedUntil() {
        return restrictedUntil;
    }

    public UserRole getNewRole() {
        return newRole;
    }

    public LocalDateTime getOccurredAt() {
        return occurredAt;
    }
}
