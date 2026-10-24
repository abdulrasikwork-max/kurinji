package com.safesphere.backend.social.service;

import com.safesphere.backend.social.dto.Relationship;
import com.safesphere.backend.social.entity.Block;
import com.safesphere.backend.social.entity.Comment;
import com.safesphere.backend.social.entity.Connection;
import com.safesphere.backend.social.entity.ConnectionStatus;
import com.safesphere.backend.social.entity.Mute;
import com.safesphere.backend.social.entity.Post;
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
                !post.getVisibleFrom().isAfter(now), post.getModeration());
    }

    public boolean canSeePost(long viewerId, Post post, LocalDateTime now) {
        return Visibility.canSeePost(postFacts(viewerId, post, now));
    }

    /**
     * Phase 13C: what lists need to know about the viewer, gathered once: whom they blocked or were blocked by, whom
     * they muted, whom they are connected with. [postFacts] then builds the same facts the one rule always gets.
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

    /** Phase 13C: the facts about [viewer] and [post] from the gathered sets; [activeAuthors] from SocialAccess. */
    public static Visibility.PostFacts postFacts(Viewer viewer, Post post, Set<Long> activeAuthors, LocalDateTime now) {
        long authorId = post.getAuthorId();
        boolean self = viewer.id() == authorId;
        Visibility.Facts people = new Visibility.Facts(self, !self && viewer.blockedEitherWay().contains(authorId),
                self || activeAuthors.contains(authorId));
        return new Visibility.PostFacts(people, !self && viewer.connected().contains(authorId), post.getAudience(),
                !post.getVisibleFrom().isAfter(now), post.getModeration());
    }

    /** Phase 13C: the posts of [candidates] that may appear in a list for [viewer] (Visibility.showInLists). */
    public List<Post> forLists(Viewer viewer, List<Post> candidates, LocalDateTime now) {
        return filter(viewer, candidates, now, false);
    }

    /** Phase 13D: the posts of [candidates] that may appear in the Edition (Visibility.showInEdition). */
    public List<Post> forEdition(Viewer viewer, List<Post> candidates, LocalDateTime now) {
        return filter(viewer, candidates, now, true);
    }

    private List<Post> filter(Viewer viewer, List<Post> candidates, LocalDateTime now, boolean edition) {
        if (candidates.isEmpty()) return List.of();
        Set<Long> active = access.activeAmong(candidates.stream().map(Post::getAuthorId).collect(Collectors.toSet()));
        List<Post> shown = new ArrayList<>();
        for (Post post : candidates) {
            Visibility.PostFacts facts = postFacts(viewer, post, active, now);
            boolean muted = viewer.muted().contains(post.getAuthorId());
            if (edition ? Visibility.showInEdition(facts, muted) : Visibility.showInLists(facts, muted)) shown.add(post);
        }
        return shown;
    }

    /**
     * Phase 13D: the facts about [viewerId] and perspective [c] on [post] (which the viewer can see). [writerFacts]
     * caches Facts per writer for one list.
     */
    public Visibility.CommentFacts commentFacts(long viewerId, Post post, Comment c,
                                                Map<Long, Visibility.Facts> writerFacts) {
        Visibility.Facts writer = writerFacts.computeIfAbsent(c.getAuthorId(), id -> facts(viewerId, id));
        return new Visibility.CommentFacts(writer, post.getAuthorId() == viewerId, c.isHidden(), c.isAwaitingApproval(),
                c.getModeration());
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
