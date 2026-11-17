package com.safesphere.backend.social.service;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;

/**
 * Phase N3: what the social features need to know about community membership, and nothing more. Implemented by the
 * community package (MembershipLookup), so social depends on this small port and never on the community package itself
 * (the community package may use social's pure media helpers without an import cycle).
 */
public interface MembershipGate {

    /** The community of [userId]'s ACTIVE membership now (not ended, not past the graduation end), if any. */
    Optional<Long> activeCommunityOf(long userId);

    /**
     * Phase N4: what moments need to know about an active membership: the community, and the exact moment it was
     * approved (memberships.verified_at), from which the 7-day new-member period is computed live (N-56); and the
     * programme (its code) and graduation year a verifier saw on the ID card, shown on the profile (N-51).
     */
    record Member(long communityId, java.time.LocalDateTime verifiedAt, String programme, int graduationYear) {
    }

    /** Phase N4: [userId]'s ACTIVE membership now, if any. */
    Optional<Member> activeMember(long userId);

    /** The ids among [userIds] with an active membership of [communityId] now (one query for a list). */
    Set<Long> activeMembersOf(long communityId, Collection<Long> userIds);
}
