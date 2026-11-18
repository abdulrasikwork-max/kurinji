package com.safesphere.backend.push.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * N7 (D31 = N-123, V38 "push_settings"): the person's switches, kept on the server so "off" means IRL does not send.
 * No row = the defaults (everything on except staff).
 */
@Entity
@Table(name = "push_settings")
public class PushSetting {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "replies", nullable = false)
    private boolean replies;

    @Column(name = "plans", nullable = false)
    private boolean plans;

    @Column(name = "plan_messages", nullable = false)
    private boolean planMessages;

    @Column(name = "people", nullable = false)
    private boolean people;

    @Column(name = "staff", nullable = false)
    private boolean staff;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** Required by JPA. */
    protected PushSetting() {
    }

    /** The defaults: everything on except the staff summary. */
    public PushSetting(long userId, LocalDateTime now) {
        this.userId = userId;
        this.replies = true;
        this.plans = true;
        this.planMessages = true;
        this.people = true;
        this.staff = false;
        this.updatedAt = now;
    }

    public void change(boolean replies, boolean plans, boolean planMessages, boolean people, boolean staff,
                       LocalDateTime now) {
        this.replies = replies;
        this.plans = plans;
        this.planMessages = planMessages;
        this.people = people;
        this.staff = staff;
        this.updatedAt = now;
    }

    public boolean isOn(PushType.Switch toggle) {
        return switch (toggle) {
            case REPLIES -> replies;
            case PLANS -> plans;
            case PLAN_MESSAGES -> planMessages;
            case PEOPLE -> people;
            case STAFF -> staff;
            case NONE -> true;
        };
    }

    public Long getUserId() {
        return userId;
    }

    public boolean isReplies() {
        return replies;
    }

    public boolean isPlans() {
        return plans;
    }

    public boolean isPlanMessages() {
        return planMessages;
    }

    public boolean isPeople() {
        return people;
    }

    public boolean isStaff() {
        return staff;
    }
}
