package com.safesphere.backend.social.service;

import com.safesphere.backend.social.dto.Relationship;
import com.safesphere.backend.social.entity.Block;
import com.safesphere.backend.social.entity.Connection;
import com.safesphere.backend.social.entity.ConnectionStatus;
import com.safesphere.backend.social.entity.Moment;
import com.safesphere.backend.social.entity.Mute;
import com.safesphere.backend.social.policy.Visibility;
import com.safesphere.backend.social.repository.BlockRepository;
import com.safesphere.backend.social.repository.ConnectionRepository;
import com.safesphere.backend.social.repository.MuteRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Phase 13A: gathers the facts the one Visibility rule needs, and the viewer's relationship with someone. Reads only.
 */
@Component
public class Relationships {

    private final BlockRepository blocks;
    private final ConnectionRepository connections;
    private final MuteRepository mutes;
    private final SocialAccess access;

    public Relationships(BlockRepository blocks, ConnectionRepository connections, MuteRepository mutes,
                         SocialAccess access) {
        this.blocks = blocks;
        this.connections = connections;
        this.mutes = mutes;
        this.access = access;
    }

    public Visibility.Facts facts(long viewerId, long ownerId) {
        boolean self = viewerId == ownerId;
        return new Visibility.Facts(self, !self && blocks.existsEitherWay(viewerId, ownerId),
                self || access.isActive(ownerId), self || access.sameCommunity(viewerId, ownerId));
    }

    public boolean canSeeProfile(long viewerId, long ownerId) {
        return Visibility.canSeeProfile(facts(viewerId, ownerId));
    }

    /** Phase 13B: an accepted connection between the two (never true for oneself). */
    public boolean connected(long a, long b) {
        return a != b && pair(a, b).map(c -> c.getStatus() == ConnectionStatus.ACCEPTED).orElse(false);
    }

    /**
     * What lists need to know about the viewer, gathered once: whom they blocked or were blocked by, whom
     * they muted, whom they are connected with.
     */
    public record Viewer(long id, Set<Long> blockedEitherWay, Set<Long> muted, Set<Long> connected) {
    }

    public Viewer viewer(long viewerId) {
        Set<Long> blocked = new HashSet<>();
        for (Block block : blocks.findInvolving(viewerId)) {
            blocked.add(block.getBlockerId() == viewerId ? block.getBlockedId() : block.getBlockerId());
        }
        Set<Long> muted = new HashSet<>();
        for (Mute mute : mutes.findByMuterIdOrderByCreatedAtDesc(viewerId)) muted.add(mute.getMutedId());
        Set<Long> connected = new HashSet<>();
        for (Connection c : connections.findInvolving(viewerId, ConnectionStatus.ACCEPTED)) {
            connected.add(c.getUserLow() == viewerId ? c.getUserHigh() : c.getUserLow());
        }
        return new Viewer(viewerId, blocked, muted, connected);
    }

    // ---- Phase N4: moments ----

    /** The facts the one rule needs about [viewerId] and [moment] at [now] (one moment, opened deliberately). */
    public Visibility.MomentFacts momentFacts(long viewerId, Moment moment, LocalDateTime now) {
        long authorId = moment.getAuthorId();
        boolean self = viewerId == authorId;
        Visibility.Facts people = facts(viewerId, authorId);
        if (!self && people.sameCommunity()) {
            // The author is an active member of the viewer's community; the moment must belong to that community too.
            boolean sameWall = access.communityOf(viewerId).map(c -> c.equals(moment.getCommunityId())).orElse(false);
            people = new Visibility.Facts(false, people.blockedEitherWay(), people.ownerActive(), sameWall);
        }
        return momentFacts(people, !self && connected(viewerId, authorId), moment, now);
    }

    public boolean canSeeMoment(long viewerId, Moment moment, LocalDateTime now) {
        return Visibility.canSeeMoment(momentFacts(viewerId, moment, now));
    }

    /**
     * The moments of [candidates] that appear on Today for [viewer], a member of [communityId] (Visibility.showOnToday),
     * in the order given. The sets are gathered once for the whole list.
     */
    public List<Moment> forToday(Viewer viewer, long communityId, List<Moment> candidates, LocalDateTime now) {
        if (candidates.isEmpty()) return List.of();
        Set<Long> authors = candidates.stream().map(Moment::getAuthorId).collect(Collectors.toSet());
        Set<Long> active = access.activeAmong(authors);
        Set<Long> sameCommunity = access.sameCommunityAmong(viewer.id(), authors);
        List<Moment> shown = new ArrayList<>();
        for (Moment moment : candidates) {
            long authorId = moment.getAuthorId();
            boolean self = viewer.id() == authorId;
            Visibility.Facts people = new Visibility.Facts(self, !self && viewer.blockedEitherWay().contains(authorId),
                    self || active.contains(authorId),
                    self || (sameCommunity.contains(authorId) && moment.getCommunityId() == communityId));
            Visibility.MomentFacts facts = momentFacts(people, !self && viewer.connected().contains(authorId), moment,
                    now);
            if (Visibility.showOnToday(facts, viewer.muted().contains(authorId))) shown.add(moment);
        }
        return shown;
    }

    private static Visibility.MomentFacts momentFacts(Visibility.Facts people, boolean connected, Moment moment,
                                                      LocalDateTime now) {
        return new Visibility.MomentFacts(people, connected, moment.getAudience(), moment.hasStarted(now),
                moment.isLive(now), moment.isKept(), moment.getModeration());
    }

    public Optional<Connection> pair(long a, long b) {
        return connections.findByUserLowAndUserHigh(Math.min(a, b), Math.max(a, b));
    }

    public Relationship relationship(long viewerId, long otherId) {
        if (viewerId == otherId) return Relationship.SELF;
        Connection connection = pair(viewerId, otherId).orElse(null);
        if (connection == null) return Relationship.NONE;
        if (connection.getStatus() == ConnectionStatus.ACCEPTED) return Relationship.CONNECTED;
        if (connection.getRequesterId() == viewerId) return Relationship.REQUESTED;
        return connection.getStatus() == ConnectionStatus.PENDING ? Relationship.REQUEST_RECEIVED : Relationship.NONE;
    }
}
