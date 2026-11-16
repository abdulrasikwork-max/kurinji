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

    /** The ids among [userIds] with an active membership of [communityId] now (one query for a list). */
    Set<Long> activeMembersOf(long communityId, Collection<Long> userIds);
}
