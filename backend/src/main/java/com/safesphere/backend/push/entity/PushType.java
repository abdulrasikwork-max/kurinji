package com.safesphere.backend.push.entity;

import java.time.Duration;

/**
 * N7 (D29 = N-121): the CLOSED list of notification kinds. A new kind needs a decision (PushPolicyTest pins this list; the
 * V38 CHECK holds the same names). Never: Same, Say more requests, new moments, the daily question, "set aside", a plan
 * closing by itself, connection accepted, digests, reminders to come back, anything with a number.
 *
 * Each kind knows its group (which switch, if any), its FCM priority, how long it is worth delivering and its thread
 * (the FCM collapse key prefix: one thread keeps only its latest undelivered push).
 */
public enum PushType {
    PLAN_ASK(Group.SOCIAL, Switch.PLANS, false, Duration.ofHours(24), "plan"),
    PLAN_ADDED(Group.SOCIAL, Switch.PLANS, false, Duration.ofHours(24), "plan"),
    PLAN_MESSAGE(Group.SOCIAL, Switch.PLAN_MESSAGES, false, Duration.ofHours(24), "plan"),
    PLAN_CALLED_OFF(Group.SOCIAL, Switch.PLANS, false, Duration.ofHours(24), "plan"),
    REPLY_TURN(Group.SOCIAL, Switch.REPLIES, false, Duration.ofHours(24), "reply"),
    MORE_ADDED(Group.SOCIAL, Switch.REPLIES, false, Duration.ofHours(24), "more"),
    PEOPLE_REQUEST(Group.SOCIAL, Switch.PEOPLE, false, Duration.ofHours(72), "people"),
    ACCOUNT_SIGN_IN(Group.ACCOUNT, Switch.NONE, false, Duration.ofHours(72), "account"),
    MEMBERSHIP_DECIDED(Group.ACCOUNT, Switch.NONE, false, Duration.ofHours(72), "account"),
    CONTENT_DECIDED(Group.ACCOUNT, Switch.NONE, false, Duration.ofHours(72), "content"),
    CIRCLE_REQUEST(Group.SAFETY, Switch.NONE, false, Duration.ofHours(72), "circle"),
    CIRCLE_SOS(Group.SAFETY, Switch.NONE, true, Duration.ofMinutes(30), "sos"),
    OMW_REMINDER(Group.SAFETY, Switch.NONE, true, Duration.ofMinutes(10), "omw"),
    STAFF_WAITING(Group.STAFF, Switch.STAFF, false, Duration.ofHours(2), "staff");

    /** What a kind is about. Only SOCIAL counts against the per-person caps. */
    public enum Group { SOCIAL, ACCOUNT, SAFETY, STAFF }

    /** The person's switch that turns a kind off (NONE: not switched in IRL, D31). */
    public enum Switch { REPLIES, PLANS, PLAN_MESSAGES, PEOPLE, STAFF, NONE }

    private final Group group;
    private final Switch toggle;
    private final boolean highPriority;
    private final Duration lifetime;
    private final String thread;

    PushType(Group group, Switch toggle, boolean highPriority, Duration lifetime, String thread) {
        this.group = group;
        this.toggle = toggle;
        this.highPriority = highPriority;
        this.lifetime = lifetime;
        this.thread = thread;
    }

    public Group group() {
        return group;
    }

    public Switch toggle() {
        return toggle;
    }

    public boolean highPriority() {
        return highPriority;
    }

    public Duration lifetime() {
        return lifetime;
    }

    public String thread() {
        return thread;
    }
}
