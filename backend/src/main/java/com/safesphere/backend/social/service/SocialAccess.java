package com.safesphere.backend.social.service;

import com.safesphere.backend.common.exception.AgeNotConfirmedException;
import com.safesphere.backend.common.exception.ForbiddenException;
import com.safesphere.backend.common.exception.MembershipNeededException;
import com.safesphere.backend.common.exception.UnauthorizedException;
import com.safesphere.backend.config.SocialProperties;
import com.safesphere.backend.social.entity.SocialStanding;
import com.safesphere.backend.social.policy.MomentPolicy;
import com.safesphere.backend.social.repository.SocialStandingRepository;
import com.safesphere.backend.user.entity.User;
import com.safesphere.backend.user.repository.UserRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import org.springframework.stereotype.Component;

/**
 * Phase 13A: who may use the social features at all. The caller's account must be active (ActiveAccountCheck already
 * refuses tokens of disabled or erased accounts; checked again here for clarity) and must have declared an adult date
 * of birth (D6). Safety features never ask this: an SOS works for every signed-in account.
 *
 * Phase N3: and must be an ACTIVE member of a community (MembershipGate): verified for MIET, not graduated, not ended,
 * not banned. A ban or an end of membership never reaches any safety feature (no safety package asks this class).
 *
 * Phase 13D: a moderator's pause (SocialStanding) stops exactly the writes it names (M9) and nothing else: reading,
 * blocking, muting, reporting, deleting and narrowing always work, and no safety feature ever asks. New accounts are on
 * probation (M16) for app.social.probation.
 */
@Component
public class SocialAccess {

    static final String POSTING_PAUSED = "A moderator has paused your sharing for now. "
            + "You shows until when, and you can ask for a review there.";
    static final String SUSPENDED = "A moderator has paused this for your account for now. "
            + "You shows until when, and you can ask for a review there.";

    private final UserRepository users;
    private final SocialStandingRepository standings;
    private final SocialProperties properties;
    private final MembershipGate membership;
    private final Clock clock;

    public SocialAccess(UserRepository users, SocialStandingRepository standings, SocialProperties properties,
                        MembershipGate membership, Clock clock) {
        this.users = users;
        this.standings = standings;
        this.properties = properties;
        this.membership = membership;
        this.clock = clock;
    }

    /**
     * Phase N3: an active, adult account, with or without a community membership. Only for the person's OWN things that
     * do not show anyone else: their profile draft and photo, and their moderation standing, appeals and review
     * requests (no shadow decisions, even after a membership ended).
     */
    public User requireAccount(long userId) {
        User user = users.findById(userId)
                .filter(u -> u.isEnabled() && !u.isErased())
                .orElseThrow(() -> new UnauthorizedException("Account is not available"));
        if (!user.isAgeConfirmed()) throw new AgeNotConfirmedException();
        return user;
    }

    /**
     * Phase N3: [requireAccount] + an ACTIVE community membership (verified, not ended, not past 31 July of the
     * graduation year, not banned). Every community read and write asks this; 403 MEMBERSHIP_NEEDED otherwise.
     */
    public User requireMember(long userId) {
        User user = requireAccount(userId);
        if (membership.activeCommunityOf(userId).isEmpty()) throw new MembershipNeededException();
        return user;
    }

    /**
     * Phase N4: [requireMember], returning the membership facts moments need (the community and the approval time).
     * 403 MEMBERSHIP_NEEDED without an active membership.
     */
    public MembershipGate.Member requireMembership(long userId) {
        requireAccount(userId);
        return membership.activeMember(userId).orElseThrow(MembershipNeededException::new);
    }

    /** Phase N4: the community of [userId]'s active membership now, if any. */
    public Optional<Long> communityOf(long userId) {
        return membership.activeCommunityOf(userId);
    }

    /** Phase N3: the person's own profile draft and photo (no membership needed); a suspension still stops changes. */
    public User requireCanEditOwnProfile(long userId) {
        User user = requireAccount(userId);
        refuseIf(userId, SocialStanding.Restriction::stopsConnecting, SUSPENDED);
        return user;
    }

    /** Phase N3: whether [ownerId] is an active member of [viewerId]'s community (false when the viewer has none). */
    public boolean sameCommunity(long viewerId, long ownerId) {
        Optional<Long> community = membership.activeCommunityOf(viewerId);
        return community.isPresent() && membership.activeMembersOf(community.get(), Set.of(ownerId)).contains(ownerId);
    }

    /** Phase N3: the ids among [userIds] that are active members of [viewerId]'s community (one query for a list). */
    public Set<Long> sameCommunityAmong(long viewerId, Collection<Long> userIds) {
        Optional<Long> community = membership.activeCommunityOf(viewerId);
        if (community.isEmpty() || userIds.isEmpty()) return Set.of();
        return membership.activeMembersOf(community.get(), userIds);
    }

    /** Phase 13D: a member who may share moments and photos now. */
    public User requireCanPost(long userId) {
        User user = requireMember(userId);
        refuseIf(userId, SocialStanding.Restriction::stopsPosting, POSTING_PAUSED);
        return user;
    }

    /** Phase 13D: a member who may send connection requests, make or use invite codes and change their profile now. */
    public User requireCanConnect(long userId) {
        User user = requireMember(userId);
        refuseIf(userId, SocialStanding.Restriction::stopsConnecting, SUSPENDED);
        return user;
    }

    /** Phase 13D: the pause in force for [userId], if any. */
    public Optional<SocialStanding> pauseOf(long userId) {
        LocalDateTime now = now();
        return standings.findById(userId).filter(s -> s.inForce(now));
    }

    /** Phase N4: the ACTIVE membership of [userId] now, if any (their programme and year for a profile; N-51). */
    public Optional<MembershipGate.Member> memberOf(long userId) {
        return membership.activeMember(userId);
    }

    /**
     * Phase N4 (N-56): whether [userId] is in the 7-day new-member period at [now]. Someone without an active
     * membership is not a member at all, so not a new one.
     */
    public boolean isNewMember(long userId, LocalDateTime now) {
        return membership.activeMember(userId).map(m -> MomentPolicy.isNewMember(m.verifiedAt(), now)).orElse(false);
    }

    /**
     * Phase N4 (PHASE_N4 §5): when a report by [userId] starts to count toward the automatic collapse: now, or the end
     * of their new-member period (it reaches the moderators at once either way).
     */
    public LocalDateTime countsFrom(long userId, LocalDateTime now) {
        LocalDateTime established = membership.activeMember(userId)
                .map(m -> MomentPolicy.establishedAt(m.verifiedAt())).orElse(now);
        return established.isAfter(now) ? established : now;
    }

    /** Phase 13C: the ids among [userIds] whose accounts are active (one query for a list). */
    public Set<Long> activeAmong(Collection<Long> userIds) {
        Set<Long> active = new HashSet<>();
        for (User user : users.findAllById(userIds)) {
            if (user.isEnabled() && !user.isErased()) active.add(user.getId());
        }
        return active;
    }

    /** Active = can be seen by others at all (not disabled, not erased). */
    public boolean isActive(long userId) {
        return users.findById(userId).map(u -> u.isEnabled() && !u.isErased()).orElse(false);
    }

    private void refuseIf(long userId, Predicate<SocialStanding.Restriction> stops, String message) {
        pauseOf(userId).filter(s -> stops.test(s.getRestriction())).ifPresent(s -> {
            throw new ForbiddenException(message);
        });
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
