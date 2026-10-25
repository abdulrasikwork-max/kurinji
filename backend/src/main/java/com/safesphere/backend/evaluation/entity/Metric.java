package com.safesphere.backend.evaluation.entity;

/**
 * What the outcome counters count (Phase 9B, the same closed list as the V15 CHECK). Event metrics are counted when the
 * event's transaction commits; snapshot metrics once per week from the clean-up job.
 */
public enum Metric {
    /** A new signal row was created by a report. Dimension: group. */
    SIGNAL_STARTED,
    /** Phase 9D: a lone report joined the same signal one block over. Dimension: group. */
    SIGNAL_JOINED_NEIGHBOUR,
    /** A signal was first seen confirmed by independent people (place_signals.reached_confirmed set). Dimension: group. */
    SIGNAL_CONFIRMED,
    /** A signal row was deleted. Dimension: {group}.{EXPIRED|RETIRED|WITHDRAWN}_{CONFIRMED|UNCONFIRMED}. */
    SIGNAL_ENDED,
    /** A moderator removed a signal. Dimension: {group}.{CONFIRMED|UNCONFIRMED}_{reason}. */
    SIGNAL_REMOVED,
    /** A review case was opened. Dimension: the trigger (FLAGS, REREPORTED, LINKED_GROUP, SUPPORT_CORE, ...). */
    CASE_OPENED,
    /** A moderator closed a case. Dimension: {trigger}.{REMOVED|DISMISSED}. */
    CASE_CLOSED,
    /** A signal was deleted while a case about it was still open (nobody reviewed it in time). Dimension: trigger. */
    CASE_UNREVIEWED,
    /** coord-1 in SHADOW found a group it would have put in front of a moderator (new this week). Dimension: rule. */
    COORDINATION_SHADOW,
    /** The weekly snapshot was taken (value 1), so an empty week can be told apart from a missing one. */
    SNAPSHOT_TAKEN,
    /** Weekly snapshot: visible signals. Dimension: {group}.{UNCONFIRMED|CONFIRMED|STRONG}. */
    VISIBLE_SIGNALS,
    /** Weekly snapshot: ~1 km areas with a visible signal, by the active rules' information level. */
    AREAS_BY_INFORMATION,
    /** Weekly snapshot: ~1 km areas with a visible signal, by the active rules' outlook. */
    AREAS_BY_OUTLOOK,
    /** Weekly snapshot, rules-2 in SHADOW: per area, SAME / LESS_CAUTIOUS / MORE_CAUTIOUS concern than the active rules. */
    SHADOW_PICTURE,
    /** Phase 13E (V24): the automatic check decided about a post or perspective. Dimension: {action}.{rule}. */
    AI_CHECKED,
    /** Phase 13E (V24): a moderator decided about content the check had collapsed or held. Dimension: {state}.{action}. */
    AI_REVIEWED
}
