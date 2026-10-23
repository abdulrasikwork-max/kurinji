package com.safesphere.backend.social.service;

import com.safesphere.backend.social.dto.Relationship;
import com.safesphere.backend.social.entity.Connection;
import com.safesphere.backend.social.entity.ConnectionStatus;
import com.safesphere.backend.social.entity.Post;
import java.time.LocalDateTime;
import com.safesphere.backend.social.policy.Visibility;
import com.safesphere.backend.social.repository.BlockRepository;
import com.safesphere.backend.social.repository.ConnectionRepository;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Phase 13A: gathers the facts the one Visibility rule needs, and the viewer's relationship with someone. Reads only.
 */
@Component
public class Relationships {

    private final BlockRepository blocks;
    private final ConnectionRepository connections;
    private final SocialAccess access;

    public Relationships(BlockRepository blocks, ConnectionRepository connections, SocialAccess access) {
        this.blocks = blocks;
        this.connections = connections;
        this.access = access;
    }

    public Visibility.Facts facts(long viewerId, long ownerId) {
        boolean self = viewerId == ownerId;
        return new Visibility.Facts(self, !self && blocks.existsEitherWay(viewerId, ownerId),
                self || access.isActive(ownerId));
    }

    public boolean canSeeProfile(long viewerId, long ownerId) {
        return Visibility.canSeeProfile(facts(viewerId, ownerId));
    }

    /** Phase 13B: an accepted connection between the two (never true for oneself). */
    public boolean connected(long a, long b) {
        return a != b && pair(a, b).map(c -> c.getStatus() == ConnectionStatus.ACCEPTED).orElse(false);
    }

    /** Phase 13B: the facts the one rule needs about [viewerId] and [post] at [now]. */
    public Visibility.PostFacts postFacts(long viewerId, Post post, LocalDateTime now) {
        long authorId = post.getAuthorId();
        return new Visibility.PostFacts(facts(viewerId, authorId), connected(viewerId, authorId), post.getAudience(),
                !post.getVisibleFrom().isAfter(now));
    }

    public boolean canSeePost(long viewerId, Post post, LocalDateTime now) {
        return Visibility.canSeePost(postFacts(viewerId, post, now));
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
