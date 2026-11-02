package com.safesphere.backend.social.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/**
 * Phase 17E (D-7, V28): one person's state for today, from a closed list of moods and intents. At most one row per
 * person; [setOn] is the India (IST) calendar day it was chosen (no time is stored). It counts only on that day: from
 * midnight IST nobody sees it any more (checked on read) and the clean-up deletes the row. No history is kept, nobody is
 * notified, and only the person and their connections ever see it.
 */
@Entity
@Table(name = "daily_states")
public class DailyState {

    /**
     * The closed list. A mood or an intent, never a place or presence: no "travelling", "at home", "free now" or
     * "online" (where someone is, or that they are reachable right now, is what a stalker wants to know).
     */
    public enum Kind { CURIOUS, PLANNING, INSPIRED, LEARNING, CELEBRATING, SLOW_DAY, BUSY_DAY, QUIET_DAY }

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Kind state;

    @Column(name = "set_on", nullable = false)
    private LocalDate setOn;

    /** Required by JPA. */
    protected DailyState() {
    }

    public DailyState(long userId, Kind state, LocalDate setOn) {
        this.userId = userId;
        this.state = state;
        this.setOn = setOn;
    }

    public void change(Kind state, LocalDate setOn) {
        this.state = state;
        this.setOn = setOn;
    }

    public Long getUserId() {
        return userId;
    }

    public Kind getState() {
        return state;
    }

    public LocalDate getSetOn() {
        return setOn;
    }
}
