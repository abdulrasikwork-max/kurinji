package com.safesphere.backend.community.entity;

import java.time.Duration;

/**
 * What a place signal says (Phase 6). A closed allow-list with no free text. Every category describes a PLACE or
 * something that HAPPENED at a place, never a person or a group of people, so the feature cannot be used to profile
 * or accuse anyone (no "suspicious person"). The same names are in the V12 CHECK and on the phone.
 *
 * [lifetime] is how long one contribution keeps a signal alive: incidents fade fast (they say little about next
 * week), conditions slowly, safe havens slowest. Confirmations keep a true signal alive; nothing lives forever.
 */
public enum SignalCategory {
    HARASSMENT(SignalGroup.INCIDENT),
    FOLLOWING(SignalGroup.INCIDENT),
    THEFT_OR_SNATCHING(SignalGroup.INCIDENT),

    POOR_LIGHTING(SignalGroup.CONDITION),
    ISOLATED_STRETCH(SignalGroup.CONDITION),
    UNSAFE_CROSSING(SignalGroup.CONDITION),
    BROKEN_PATH(SignalGroup.CONDITION),

    WELL_LIT_BUSY(SignalGroup.HAVEN),
    OPEN_LATE_STAFFED(SignalGroup.HAVEN),
    HELP_POINT(SignalGroup.HAVEN);

    private final SignalGroup group;

    SignalCategory(SignalGroup group) {
        this.group = group;
    }

    public SignalGroup group() {
        return group;
    }

    public Duration lifetime() {
        return group.lifetime();
    }

    public enum SignalGroup {
        /** Something that happened (concern). */
        INCIDENT(Duration.ofDays(3)),
        /** A lasting condition of the place (caution). */
        CONDITION(Duration.ofDays(30)),
        /** A place that helps people feel or be safer (positive). */
        HAVEN(Duration.ofDays(60));

        private final Duration lifetime;

        SignalGroup(Duration lifetime) {
            this.lifetime = lifetime;
        }

        public Duration lifetime() {
            return lifetime;
        }
    }
}
