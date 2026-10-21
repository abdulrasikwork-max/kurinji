package com.safesphere.backend.emergencycontext;

/**
 * Who an Emergency Context Packet projection is for (approved Phase 4 design). Each audience gets a FIELD ALLOW-LIST,
 * never "everything minus a few fields", so adding data later cannot leak it by accident.
 */
public enum ContextAudience {
    /** The person themselves: everything recorded. */
    OWNER,
    /** What a Safety Circle contact may see: name, time, type, status, a location link. */
    SAFETY_CIRCLE,
    /** Future official emergency pathway. Not available: no integration exists. */
    OFFICIAL_PATHWAY,
    /** Future Verified Nearby Assistance (Phase 12). Reserved, always refused. */
    VERIFIED_NEARBY_ASSISTANCE;

    public boolean isAvailable() {
        return this == OWNER || this == SAFETY_CIRCLE;
    }
}
