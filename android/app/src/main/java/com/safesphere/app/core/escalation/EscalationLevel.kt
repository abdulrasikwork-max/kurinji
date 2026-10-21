package com.safesphere.app.core.escalation

/**
 * The Escalation Ladder, same codes as the backend. Proportional: the automatic check-in policy only moves
 * 0 -> 1 -> 2; level 3 needs the person to confirm SOS; 4 (dial prompt) arrives in Phase 4D; 5 is reserved.
 */
enum class EscalationLevel(val code: Int) {
    NORMAL(0),
    CHECK_IN_MISSED(1),
    NO_RESPONSE(2),
    EMERGENCY(3),
    EMERGENCY_PATHWAY(4),
    VERIFIED_NEARBY_ASSISTANCE(5);

    companion object {
        fun fromCode(code: Int): EscalationLevel = entries.firstOrNull { it.code == code } ?: NORMAL
    }
}
