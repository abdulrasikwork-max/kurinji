package com.safesphere.backend.social.service;

import com.safesphere.backend.common.exception.ConflictException;
import com.safesphere.backend.common.exception.InvalidRequestException;
import com.safesphere.backend.common.exception.ResourceNotFoundException;
import com.safesphere.backend.social.dto.CommentDtos.CommentRequest;
import com.safesphere.backend.social.dto.CommentDtos.CommentResponse;
import com.safesphere.backend.social.entity.Comment;
import com.safesphere.backend.social.entity.Post;
import com.safesphere.backend.social.entity.PostEnums.CommentKind;
import com.safesphere.backend.social.entity.PostEnums.PostType;
import com.safesphere.backend.social.entity.Profile;
import com.safesphere.backend.social.policy.PostPolicy;
import com.safesphere.backend.social.policy.TimeBands;
import com.safesphere.backend.social.policy.Visibility;
import com.safesphere.backend.social.repository.CommentRepository;
import com.safesphere.backend.social.repository.PostRepository;
import com.safesphere.backend.social.repository.ProfileRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 13B: Perspectives (design §3.3). On an Experience: Been here too (shown first) and Question; on an Ask: Answer
 * (those the asker marked "This helped" first) and Question; a Reply sits under one of those (one level). Who may write
 * is the post author's choice (the one Visibility rule, canComment). The author can hide any perspective on their post:
 * its writer still sees it marked hidden (Q5), nobody else sees it or the replies under it. A perspective by someone the
 * viewer blocked or who blocked the viewer is left out for that viewer. "Not allowed" is always "not found".
 */
@Service
public class CommentService {

    static final String NOT_FOUND = "Perspective not found";
    static final int BODY_MAX = 1000;

    private final CommentRepository comments;
    private final PostRepository posts;
    private final PostService postService;
    private final ProfileRepository profiles;
    private final Relationships relationships;
    private final SocialAccess access;
    private final SocialLimits limits;
    private final Clock clock;

    public CommentService(CommentRepository comments, PostRepository posts, PostService postService,
                          ProfileRepository profiles, Relationships relationships, SocialAccess access,
                          SocialLimits limits, Clock clock) {
        this.comments = comments;
        this.posts = posts;
        this.postService = postService;
        this.profiles = profiles;
        this.relationships = relationships;
        this.access = access;
        this.limits = limits;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<CommentResponse> list(long viewerId, String postId) {
        access.requireMember(viewerId);
        LocalDateTime now = now();
        Post post = postService.requireVisible(viewerId, postId, now);
        List<Comment> all = comments.findByPostIdOrderByCreatedAtAscIdAsc(post.getId());
        Map<Long, Boolean> authorVisible = new HashMap<>();
        List<Comment> visible = all.stream().filter(c -> visibleTo(viewerId, post, c, authorVisible)).toList();
        Set<Long> visibleIds = visible.stream().map(Comment::getId).collect(Collectors.toSet());

        Comparator<Comment> topOrder = Comparator.comparingInt((Comment c) -> rank(post, c))
                .thenComparing(Comment::getCreatedAt).thenComparing(Comment::getId);
        List<Comment> top = visible.stream().filter(c -> c.getParentId() == null).sorted(topOrder).toList();
        Map<Long, List<Comment>> replies = visible.stream()
                .filter(c -> c.getParentId() != null && visibleIds.contains(c.getParentId()))
                .collect(Collectors.groupingBy(Comment::getParentId));

        Map<Long, Profile> authors = profiles.findByUserIdIn(visible.stream().map(Comment::getAuthorId).distinct().toList())
                .stream().collect(Collectors.toMap(Profile::getUserId, Function.identity()));
        Map<Long, String> publicIds = visible.stream().collect(Collectors.toMap(Comment::getId, Comment::getPublicId));
        List<CommentResponse> out = new ArrayList<>();
        for (Comment parent : top) {
            out.add(respond(viewerId, post, parent, authors, publicIds, now));
            for (Comment reply : replies.getOrDefault(parent.getId(), List.of())) {
                out.add(respond(viewerId, post, reply, authors, publicIds, now));
            }
        }
        return out;
    }

    @Transactional
    public CommentResponse create(long userId, String postId, CommentRequest request) {
        access.requireMember(userId);
        if (profiles.findByUserId(userId).isEmpty()) throw new ConflictException(PostService.PROFILE_NEEDED);
        LocalDateTime now = now();
        Post post = postService.requireVisible(userId, postId, now);
        if (!Visibility.canComment(relationships.postFacts(userId, post, now), post.getCommentPolicy())) {
            throw new ResourceNotFoundException(PostService.NOT_FOUND);
        }
        limits.comment(userId);
        CommentKind kind = PostService.parse(CommentKind.class, request.kind(), "Choose a kind of perspective");
        Long parentId = null;
        if (request.parentId() == null || request.parentId().isBlank()) {
            boolean allowed = switch (kind) {
                case BEEN_HERE -> post.getType() == PostType.EXPERIENCE;
                case ANSWER -> post.getType() == PostType.ASK;
                case QUESTION -> true;
                case REPLY -> false;
            };
            if (!allowed) throw new InvalidRequestException("That kind of perspective does not fit this post");
        } else {
            if (kind != CommentKind.REPLY) throw new InvalidRequestException("An answer to a perspective is a reply");
            Comment parent = comments.findByPublicId(request.parentId()).orElse(null);
            if (parent == null || !parent.getPostId().equals(post.getId()) || parent.getParentId() != null
                    || !visibleTo(userId, post, parent, new HashMap<>())) {
                throw new ResourceNotFoundException(NOT_FOUND);
            }
            parentId = parent.getId();
        }
        String body = PostPolicy.cleanBody(request.body());
        int length = PostPolicy.length(body);
        if (length < 1) throw new InvalidRequestException("Write your perspective");
        if (length > BODY_MAX) throw new InvalidRequestException("A perspective has at most 1000 characters");
        Comment comment = comments.saveAndFlush(new Comment(post.getId(), userId, parentId, kind, body, now));
        Map<Long, Profile> authors = profiles.findByUserIdIn(List.of(userId)).stream()
                .collect(Collectors.toMap(Profile::getUserId, Function.identity()));
        Map<Long, String> publicIds = new HashMap<>();
        if (parentId != null) publicIds.put(parentId, request.parentId());
        return respond(userId, post, comment, authors, publicIds, now);
    }

    /** Its writer deletes it (with the replies under it). */
    @Transactional
    public void delete(long userId, String commentId) {
        access.requireMember(userId);
        Comment comment = comments.findByPublicId(commentId).filter(c -> c.getAuthorId() == userId)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND));
        comments.deleteReplies(comment.getId());
        comments.delete(comment);
    }

    /** The post's author hides or shows again a perspective on their post. */
    @Transactional
    public void setHidden(long userId, String commentId, boolean hidden) {
        access.requireMember(userId);
        Comment comment = onOwnPost(userId, commentId);
        if (hidden) comment.hide(now()); else comment.unhide();
        comments.saveAndFlush(comment);
    }

    /** The asker marks an answer on their Ask "This helped" (or takes it back). */
    @Transactional
    public void setHelped(long userId, String commentId, boolean helped) {
        access.requireMember(userId);
        Comment comment = onOwnPost(userId, commentId);
        Post post = posts.findById(comment.getPostId()).orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND));
        if (post.getType() != PostType.ASK || comment.getKind() != CommentKind.ANSWER) {
            throw new ResourceNotFoundException(NOT_FOUND);
        }
        if (helped) comment.markHelped(now()); else comment.unmarkHelped();
        comments.saveAndFlush(comment);
    }

    private Comment onOwnPost(long userId, String commentId) {
        Comment comment = comments.findByPublicId(commentId).orElse(null);
        if (comment == null) throw new ResourceNotFoundException(NOT_FOUND);
        Post post = posts.findById(comment.getPostId()).orElse(null);
        if (post == null || post.getAuthorId() != userId) throw new ResourceNotFoundException(NOT_FOUND);
        return comment;
    }

    /**
     * Whether [viewerId] may see [c] on a post they can see: its writer must be visible to them (active, no block
     * either way); a hidden one only to its writer and the post's author.
     */
    private boolean visibleTo(long viewerId, Post post, Comment c, Map<Long, Boolean> authorVisible) {
        boolean writer = c.getAuthorId() == viewerId;
        boolean postAuthor = post.getAuthorId() == viewerId;
        if (c.isHidden() && !writer && !postAuthor) return false;
        return writer || authorVisible.computeIfAbsent(c.getAuthorId(),
                id -> relationships.canSeeProfile(viewerId, id));
    }

    /** Been here too first on an Experience; answers marked helpful first on an Ask. */
    private static int rank(Post post, Comment c) {
        if (post.getType() == PostType.EXPERIENCE) return c.getKind() == CommentKind.BEEN_HERE ? 0 : 1;
        return c.isHelped() ? 0 : 1;
    }

    private CommentResponse respond(long viewerId, Post post, Comment c, Map<Long, Profile> authors,
                                    Map<Long, String> publicIds, LocalDateTime now) {
        boolean own = c.getAuthorId() == viewerId;
        boolean postAuthor = post.getAuthorId() == viewerId;
        Profile author = authors.get(c.getAuthorId());
        return new CommentResponse(c.getPublicId(), c.getKind().name(),
                author == null ? null : ProfileService.summary(author), own, c.getBody(),
                TimeBands.band(c.getCreatedAt(), now).name(), c.isHidden() && (own || postAuthor), c.isHelped(),
                c.getParentId() == null ? null : publicIds.get(c.getParentId()), postAuthor,
                postAuthor && post.getType() == PostType.ASK && c.getKind() == CommentKind.ANSWER);
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
}
