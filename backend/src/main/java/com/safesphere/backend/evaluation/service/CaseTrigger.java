package com.safesphere.backend.evaluation.service;

import com.safesphere.backend.community.service.CoordinationPolicy;

/**
 * What asked for a review case, as counted (Phase 9B). The moderation package maps its case origin (and, for
 * COORDINATION cases, the rule stored on the case since V15) to one of these.
 */
public enum CaseTrigger {
    /** People flagged the signal (Phase 7). */
    FLAGS,
    /** A removed signal was reported again by independent people (Phase 7). */
    REREPORTED,
    /** Phase 7's own coordination rule: all support from one linked group. */
    LINKED_GROUP,
    /** coord-1 R1 (Phase 9C). */
    SUPPORT_CORE,
    /** coord-1 R2 (Phase 9C). */
    EXCLUSIVE_PAIR,
    /** A COORDINATION case opened before V15, whose rule is unknown. */
    COORDINATION;

    /** The rule version the count belongs to: coord-1 for its two rules, '-' for the rest. */
    public String version() {
        return this == SUPPORT_CORE || this == EXCLUSIVE_PAIR ? CoordinationPolicy.VERSION : OutcomeRecorder.NONE;
    }
}
