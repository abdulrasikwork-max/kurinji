package com.safesphere.backend.social.entity;

/**
 * Phase N4 (V35): the closed lists of a moment. The V35 CHECKs use the same codes; a code is added by a migration,
 * never by free text. (The moderation state, reason and source are the lists posts used: PostEnums.)
 */
public final class MomentEnums {

    private MomentEnums() {
    }

    /** N-45: something on your mind, or a plan looking for company. Nothing else (no voice, no video: N-44). */
    public enum Kind { SAY, WHOS_IN }

    /**
     * N-46: who may see a moment. COMMUNITY = every active member of the author's community (MIET); MY_PEOPLE = the
     * author's mutual connections who are active members of it. There is no default: the author chooses.
     */
    public enum Audience { COMMUNITY, MY_PEOPLE }
}
