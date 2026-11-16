package com.safesphere.backend.community.service;

import com.safesphere.backend.community.entity.Community;
import com.safesphere.backend.community.entity.Membership;
import com.safesphere.backend.community.repository.CommunityRepository;
import com.safesphere.backend.community.repository.MembershipRepository;
import com.safesphere.backend.social.service.MembershipGate;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Phase N3: THE answer to "is this person an active member, of which community, and a verifier?". Active = a
 * membership row without an end and before its graduation end (read from [Membership#getEndsAt] directly, so access
 * stops at the right moment even before the hourly job marks the row GRADUATED). Used by the social access rule (as
 * MembershipGate), by the verifier role check (RoleAuthorization) and by the community services. Never by safety.
 */
@Component
public class MembershipLookup implements MembershipGate {

    private final MembershipRepository memberships;
    private final CommunityRepository communities;
    private final Clock clock;

    public MembershipLookup(MembershipRepository memberships, CommunityRepository communities, Clock clock) {
        this.memberships = memberships;
        this.communities = communities;
        this.clock = clock;
    }

    /** The launch community (MIET), written by V34. */
    public Community miet() {
        return communities.findByCode(Community.MIET)
                .orElseThrow(() -> new IllegalStateException("The MIET community row is missing (V34)"));
    }

    public Optional<Membership> active(long userId) {
        LocalDateTime now = now();
        return memberships.findByUserId(userId).filter(m -> m.isActive(now));
    }

    public boolean isActiveVerifier(long userId) {
        return active(userId).map(Membership::isVerifier).orElse(false);
    }

    @Override
    public Optional<Long> activeCommunityOf(long userId) {
        return active(userId).map(Membership::getCommunityId);
    }

    @Override
    public Optional<Member> activeMember(long userId) {
        return active(userId).map(m -> new Member(m.getCommunityId(), m.getVerifiedAt()));
    }

    @Override
    public Set<Long> activeMembersOf(long communityId, Collection<Long> userIds) {
        if (userIds.isEmpty()) return Set.of();
        return new HashSet<>(memberships.activeAmong(communityId, userIds, now()));
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
