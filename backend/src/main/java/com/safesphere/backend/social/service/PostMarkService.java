package com.safesphere.backend.social.service;

import com.safesphere.backend.common.exception.InvalidRequestException;
import com.safesphere.backend.social.dto.DiscoverDtos.MarkResponse;
import com.safesphere.backend.social.dto.DiscoverDtos.SavedItem;
import com.safesphere.backend.social.dto.DiscoverDtos.SavedPage;
import com.safesphere.backend.social.dto.PostDtos.PostResponse;
import com.safesphere.backend.social.entity.Butterfly;
import com.safesphere.backend.social.entity.Post;
import com.safesphere.backend.social.entity.PostEnums.PostType;
import com.safesphere.backend.social.entity.SavedPost;
import com.safesphere.backend.social.policy.TimeBands;
import com.safesphere.backend.social.repository.ButterflyRepository;
import com.safesphere.backend.social.repository.PostRepository;
import com.safesphere.backend.social.repository.SavedPostRepository;
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
 * Phase 17E (D-3): the two private marks a person can put on someone else's post.
 * <ul>
 *   <li>The butterfly = a reaction to an Experience ("this inspires me"). The author learns only a thresholded number
 *       (ButterflyTally, from 3); nobody else learns anything.</li>
 *   <li>Save = a private bookmark of an Experience or a Question. Counted for nobody.</li>
 * </ul>
 * Both are idempotent so a feed can toggle them optimistically: marking twice keeps one row; un-marking something that is
 * not marked answers the same (it reveals nothing about the post). Writes are not in a transaction of their own: a double
 * tap that loses the unique-key race simply finds the other row (never a caught unique-key error inside a transaction,
 * Phase 14). The Saved list shows only posts the owner can still see (the one Visibility rule).
 */
@Service
public class PostMarkService {

    static final String OWN_POST = "This is your own post";
    static final String ONLY_EXPERIENCES = "Only experiences get a butterfly";
    static final int PAGE_SIZE = 20;

    private static final LocalDateTime END = LocalDateTime.of(9999, 12, 31, 0, 0);

    private final ButterflyRepository butterflies;
    private final SavedPostRepository saved;
    private final PostRepository posts;
    private final PostService postService;
    private final Relationships relationships;
    private final SocialAccess access;
    private final SocialLimits limits;
    private final Clock clock;

    public PostMarkService(ButterflyRepository butterflies, SavedPostRepository saved, PostRepository posts,
                           PostService postService, Relationships relationships, SocialAccess access,
                           SocialLimits limits, Clock clock) {
        this.butterflies = butterflies;
        this.saved = saved;
        this.posts = posts;
        this.postService = postService;
        this.relationships = relationships;
        this.access = access;
        this.limits = limits;
        this.clock = clock;
    }

    /** The butterfly on someone else's Experience the caller can see. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public MarkResponse react(long userId, String postPublicId) {
        Post post = markable(userId, postPublicId);
        if (post.getType() != PostType.EXPERIENCE) throw new InvalidRequestException(ONLY_EXPERIENCES);
        limits.butterfly(userId);
        if (butterflies.findByUserIdAndPostId(userId, post.getId()).isEmpty()) {
            try {
                butterflies.saveAndFlush(new Butterfly(userId, post.getId(), now()));
            } catch (DataIntegrityViolationException e) {
                // A double tap: the other request stored it, which is what was asked.
                if (butterflies.findByUserIdAndPostId(userId, post.getId()).isEmpty()) throw e;
            }
        }
        return new MarkResponse(true);
    }

    /** Takes the caller's butterfly back; the same answer whether there was one or not. */
    @Transactional
    public void unreact(long userId, String postPublicId) {
        access.requireMember(userId);
        limits.butterfly(userId);
        Post post = postPublicId == null ? null : posts.findByPublicId(postPublicId).orElse(null);
        if (post != null) butterflies.deleteOne(userId, post.getId());
    }

    /** Saves someone else's post the caller can see (an Experience or a Question). */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public MarkResponse save(long userId, String postPublicId) {
        Post post = markable(userId, postPublicId);
        limits.butterfly(userId);
        if (saved.findByUserIdAndPostId(userId, post.getId()).isEmpty()) {
            try {
                saved.saveAndFlush(new SavedPost(userId, post.getId(), now()));
            } catch (DataIntegrityViolationException e) {
                if (saved.findByUserIdAndPostId(userId, post.getId()).isEmpty()) throw e;
            }
        }
        return new MarkResponse(true);
    }

    /** Removes it from the caller's Saved list; works even when they can no longer see the post (it is their row). */
    @Transactional
    public void unsave(long userId, String postPublicId) {
        access.requireMember(userId);
        limits.butterfly(userId);
        Post post = postPublicId == null ? null : posts.findByPublicId(postPublicId).orElse(null);
        if (post != null) saved.deleteOne(userId, post.getId());
    }

    /** The caller's Saved list, newest saved first, 20 per page; posts they can no longer see are skipped. */
    @Transactional(readOnly = true)
    public SavedPage savedList(long userId, String cursor) {
        access.requireMember(userId);
        LocalDateTime now = now();
        SavedPost after = null;
        if (cursor != null && !cursor.isBlank()) {
            Post cursorPost = posts.findByPublicId(cursor).orElse(null);
            after = cursorPost == null ? null : saved.findByUserIdAndPostId(userId, cursorPost.getId()).orElse(null);
            if (after == null) throw new InvalidRequestException("Invalid cursor");
        }
        List<SavedPost> raw = saved.page(userId, after == null ? END : after.getCreatedAt(),
                after == null ? Long.MAX_VALUE : after.getId(), PageRequest.of(0, PAGE_SIZE + 1));
        boolean more = raw.size() > PAGE_SIZE;
        List<SavedPost> rows = more ? raw.subList(0, PAGE_SIZE) : raw;
        Map<Long, Post> byId = posts.findByIdIn(rows.stream().map(SavedPost::getPostId).toList()).stream()
                .collect(Collectors.toMap(Post::getId, Function.identity()));
        List<SavedPost> shownRows = new ArrayList<>();
        List<Post> shownPosts = new ArrayList<>();
        for (SavedPost row : rows) {
            Post post = byId.get(row.getPostId());
            if (post != null && relationships.canSeePost(userId, post, now)) {
                shownRows.add(row);
                shownPosts.add(post);
            }
        }
        List<PostResponse> answers = postService.respond(userId, shownPosts, now);
        List<SavedItem> items = new ArrayList<>(answers.size());
        for (int i = 0; i < answers.size(); i++) {
            items.add(new SavedItem(answers.get(i), TimeBands.band(shownRows.get(i).getCreatedAt(), now).name()));
        }
        String next = null;
        if (more) {
            Post last = byId.get(rows.get(rows.size() - 1).getPostId());
            next = last == null ? null : last.getPublicId();
        }
        return new SavedPage(items, next);
    }

    /** Someone else's post the caller can see (404 otherwise, exactly like a post that does not exist). */
    private Post markable(long userId, String postPublicId) {
        access.requireMember(userId);
        Post post = postService.requireVisible(userId, postPublicId, now());
        if (post.getAuthorId() == userId) throw new InvalidRequestException(OWN_POST);
        return post;
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
}
