package com.safesphere.backend.social.service;

import com.safesphere.backend.common.exception.InvalidRequestException;
import com.safesphere.backend.common.exception.ResourceNotFoundException;
import com.safesphere.backend.social.dto.DiscoverDtos.ButterflyResponse;
import com.safesphere.backend.social.dto.DiscoverDtos.CocoonItem;
import com.safesphere.backend.social.dto.DiscoverDtos.CocoonPage;
import com.safesphere.backend.social.dto.PostDtos.PostResponse;
import com.safesphere.backend.social.entity.Butterfly;
import com.safesphere.backend.social.entity.Post;
import com.safesphere.backend.social.entity.PostEnums.ButterflyState;
import com.safesphere.backend.social.entity.PostEnums.PostType;
import com.safesphere.backend.social.policy.TimeBands;
import com.safesphere.backend.social.repository.ButterflyRepository;
import com.safesphere.backend.social.repository.PostRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 13C: the butterfly 🦋 = "I want to experience this", saved privately to the Cocoon (design §3.4, C1-C4). Only
 * Experiences of other people the viewer can see. The Cocoon shows only posts the owner can still see (the one
 * Visibility rule): a saved post that became invisible is hidden, not leaked, and comes back if it becomes visible.
 */
@Service
public class ButterflyService {

    static final String NOT_SAVED = "This is not in your Cocoon";
    static final String OWN_POST = "This is your own experience";
    static final String ONLY_EXPERIENCES = "Only experiences can be saved to your Cocoon";
    static final int PAGE_SIZE = 20;

    private static final LocalDateTime END = LocalDateTime.of(9999, 12, 31, 0, 0);

    private final ButterflyRepository butterflies;
    private final PostRepository posts;
    private final PostService postService;
    private final Relationships relationships;
    private final SocialAccess access;
    private final SocialLimits limits;
    private final Clock clock;

    public ButterflyService(ButterflyRepository butterflies, PostRepository posts, PostService postService,
                            Relationships relationships, SocialAccess access, SocialLimits limits, Clock clock) {
        this.butterflies = butterflies;
        this.posts = posts;
        this.postService = postService;
        this.relationships = relationships;
        this.access = access;
        this.limits = limits;
        this.clock = clock;
    }

    /**
     * Saves [postPublicId] to the caller's Cocoon (WANT) or moves it to [stateCode]. Not in a transaction of its own: a
     * double tap that loses the unique-key race reads the winner's row and applies the state to it.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ButterflyResponse put(long userId, String postPublicId, String stateCode) {
        access.requireMember(userId);
        ButterflyState state = PostService.parseOptional(ButterflyState.class, stateCode, "Choose Want or Been");
        if (state == null) state = ButterflyState.WANT;
        LocalDateTime now = now();
        Post post = postService.requireVisible(userId, postPublicId, now);
        if (post.getAuthorId() == userId) throw new InvalidRequestException(OWN_POST);
        if (post.getType() != PostType.EXPERIENCE) throw new InvalidRequestException(ONLY_EXPERIENCES);
        limits.butterfly(userId);
        Butterfly butterfly = butterflies.findByUserIdAndPostId(userId, post.getId()).orElse(null);
        if (butterfly == null) {
            try {
                butterfly = butterflies.saveAndFlush(new Butterfly(userId, post.getId(), now));
            } catch (DataIntegrityViolationException e) {
                butterfly = butterflies.findByUserIdAndPostId(userId, post.getId()).orElseThrow(() -> e);
            }
        }
        if (butterfly.getState() != state) {
            butterfly.moveTo(state, now);
            butterflies.saveAndFlush(butterfly);
        }
        return new ButterflyResponse(butterfly.getState().name());
    }

    /** Removes it from the caller's Cocoon; works even when the post is no longer visible to them (it is their row). */
    @Transactional
    public void remove(long userId, String postPublicId) {
        access.requireMember(userId);
        limits.butterfly(userId);
        Post post = postPublicId == null ? null : posts.findByPublicId(postPublicId).orElse(null);
        Butterfly butterfly = post == null ? null : butterflies.findByUserIdAndPostId(userId, post.getId()).orElse(null);
        if (butterfly == null) throw new ResourceNotFoundException(NOT_SAVED);
        butterflies.delete(butterfly);
    }

    /** The caller's Cocoon in one state, newest saved first, 20 per page; posts they can no longer see are skipped. */
    @Transactional(readOnly = true)
    public CocoonPage cocoon(long userId, String stateCode, String cursor) {
        access.requireMember(userId);
        ButterflyState state = PostService.parseOptional(ButterflyState.class, stateCode, "Choose Want or Been");
        if (state == null) state = ButterflyState.WANT;
        LocalDateTime now = now();
        Butterfly after = null;
        if (cursor != null && !cursor.isBlank()) {
            Post cursorPost = posts.findByPublicId(cursor).orElse(null);
            after = cursorPost == null ? null
                    : butterflies.findByUserIdAndPostId(userId, cursorPost.getId()).orElse(null);
            if (after == null || after.getState() != state) throw new InvalidRequestException("Invalid cursor");
        }
        List<Butterfly> raw = butterflies.cocoonPage(userId, state, after == null ? END : after.getCreatedAt(),
                after == null ? Long.MAX_VALUE : after.getId(), PageRequest.of(0, PAGE_SIZE + 1));
        boolean more = raw.size() > PAGE_SIZE;
        List<Butterfly> rows = more ? raw.subList(0, PAGE_SIZE) : raw;
        Map<Long, Post> byId = posts.findByIdIn(rows.stream().map(Butterfly::getPostId).toList()).stream()
                .collect(Collectors.toMap(Post::getId, Function.identity()));
        List<Butterfly> shownRows = new ArrayList<>();
        List<Post> shownPosts = new ArrayList<>();
        for (Butterfly row : rows) {
            Post post = byId.get(row.getPostId());
            if (post != null && relationships.canSeePost(userId, post, now)) {
                shownRows.add(row);
                shownPosts.add(post);
            }
        }
        List<PostResponse> answers = postService.respond(userId, shownPosts, now);
        List<CocoonItem> items = new ArrayList<>(answers.size());
        for (int i = 0; i < answers.size(); i++) {
            Butterfly row = shownRows.get(i);
            items.add(new CocoonItem(answers.get(i), row.getState().name(),
                    TimeBands.band(row.getCreatedAt(), now).name()));
        }
        String next = more ? byIdPublic(byId, rows.get(rows.size() - 1)) : null;
        return new CocoonPage(items, next);
    }

    private static String byIdPublic(Map<Long, Post> byId, Butterfly row) {
        Post post = byId.get(row.getPostId());
        return post == null ? null : post.getPublicId();
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
}
