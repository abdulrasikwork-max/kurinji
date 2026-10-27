package com.safesphere.backend.social.service;

import com.safesphere.backend.common.exception.AgeNotConfirmedException;
import com.safesphere.backend.common.exception.ForbiddenException;
import com.safesphere.backend.common.exception.UnauthorizedException;
import com.safesphere.backend.config.SocialProperties;
import com.safesphere.backend.social.entity.SocialStanding;
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
 * Phase 13D: a moderator's pause (SocialStanding) stops exactly the writes it names (M9) and nothing else: reading,
 * blocking, muting, reporting, deleting and narrowing always work, and no safety feature ever asks. New accounts are on
 * probation (M16) for app.social.probation.
 */
@Component
public class SocialAccess {

    static final String COMMENTS_PAUSED = "A moderator has paused your perspectives for now. "
            + "Connections shows until when, and you can ask for a review there.";
    static final String POSTING_PAUSED = "A moderator has paused your posting for now. "
            + "Connections shows until when, and you can ask for a review there.";
    static final String SUSPENDED = "A moderator has paused this for your account for now. "
            + "Connections shows until when, and you can ask for a review there.";

    private final UserRepository users;
    private final SocialStandingRepository standings;
    private final SocialProperties properties;
    private final Clock clock;

    public SocialAccess(UserRepository users, SocialStandingRepository standings, SocialProperties properties,
                        Clock clock) {
        this.users = users;
        this.standings = standings;
        this.properties = properties;
        this.clock = clock;
    }

    public User requireMember(long userId) {
        User user = users.findById(userId)
                .filter(u -> u.isEnabled() && !u.isErased())
                .orElseThrow(() -> new UnauthorizedException("Account is not available"));
        if (!user.isAgeConfirmed()) throw new AgeNotConfirmedException();
        return user;
    }

    /** Phase 13D: a member who may write perspectives now (no pause in force that stops them). */
    public User requireCanComment(long userId) {
        User user = requireMember(userId);
        refuseIf(userId, SocialStanding.Restriction::stopsComments, COMMENTS_PAUSED);
        return user;
    }

    /** Phase 13D: a member who may share posts and add places now. */
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

    /** Phase 13D (M16): an account younger than app.social.probation. */
    public boolean onProbation(User user) {
        return user.getCreatedAt() == null || user.getCreatedAt().plus(properties.probation()).isAfter(now());
    }

    /** Phase 13D (M5): when a report by [user] starts to count (now, or the end of their probation). */
    public LocalDateTime countsFrom(User user, LocalDateTime now) {
        if (user.getCreatedAt() == null) return now.plus(properties.probation());
        LocalDateTime end = user.getCreatedAt().plus(properties.probation());
        return end.isAfter(now) ? end : now;
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
