package com.safesphere.backend.social.service;

import com.safesphere.backend.common.exception.ConflictException;
import com.safesphere.backend.common.exception.InvalidRequestException;
import com.safesphere.backend.common.exception.ResourceNotFoundException;
import com.safesphere.backend.config.SocialProperties;
import com.safesphere.backend.social.dto.PostDtos.MediaRef;
import com.safesphere.backend.social.dto.PostDtos.PostEditRequest;
import com.safesphere.backend.social.dto.PostDtos.PostPage;
import com.safesphere.backend.social.dto.PostDtos.PostPlace;
import com.safesphere.backend.social.dto.PostDtos.PostRequest;
import com.safesphere.backend.social.dto.PostDtos.PostResponse;
import com.safesphere.backend.social.entity.Butterfly;
import com.safesphere.backend.social.entity.Place;
import com.safesphere.backend.social.entity.Post;
import com.safesphere.backend.social.entity.PostEnums.Audience;
import com.safesphere.backend.social.entity.PostEnums.ButterflyState;
import com.safesphere.backend.social.entity.PostEnums.City;
import com.safesphere.backend.social.entity.PostEnums.CommentPolicy;
import com.safesphere.backend.social.entity.PostEnums.Daypart;
import com.safesphere.backend.social.entity.PostEnums.PostType;
import com.safesphere.backend.social.entity.PostEnums.Precision;
import com.safesphere.backend.social.entity.PostMedia;
import com.safesphere.backend.social.entity.Profile;
import com.safesphere.backend.social.media.MediaStorage;
import com.safesphere.backend.social.policy.ButterflyTally;
import com.safesphere.backend.social.policy.PostDelay;
import com.safesphere.backend.social.policy.PostPolicy;
import com.safesphere.backend.social.policy.TimeBands;
import com.safesphere.backend.social.policy.Visibility;
import com.safesphere.backend.social.repository.ButterflyRepository;
import com.safesphere.backend.social.repository.CommentRepository;
import com.safesphere.backend.social.repository.PlaceRepository;
import com.safesphere.backend.social.repository.PostMediaRepository;
import com.safesphere.backend.social.repository.PostRepository;
import com.safesphere.backend.social.repository.ProfileRepository;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Phase 13B: Experiences and Asks. Every read goes through the one Visibility rule (Relationships.postFacts); a post the
 * viewer may not see is "not found", exactly like one that does not exist. Others get bands, never timestamps, and an
 * AREA post never reveals its place or 7-character cell. An EXACT post waits a random 1-3 h before others see it (Q1).
 * Photos are attached atomically at creation; deleting a post deletes its rows, then its photo files.
 */
@Service
public class PostService {

    static final String NOT_FOUND = "Post not found";
    static final String MEDIA_NOT_FOUND = "Photo not found";
    static final String PROFILE_NEEDED = "Make your profile first";
    static final String ONLY_NARROWER = "You can only make a post more private. To share it more widely, post it again.";
    static final String PLACE_GONE = "That place is no longer available";
    static final int PAGE_SIZE = 20;

    /** The cursor of a first page: after every post. */
    private static final LocalDateTime END = LocalDateTime.of(9999, 12, 31, 0, 0);

    private static final Logger log = LoggerFactory.getLogger(PostService.class);

    private final PostRepository posts;
    private final PostMediaRepository mediaRows;
    private final CommentRepository comments;
    private final ButterflyRepository butterflies;
    private final PlaceService placeService;
    private final PlaceRepository places;
    private final ProfileRepository profiles;
    private final ProfileService profileService;
    private final Relationships relationships;
    private final SocialAccess access;
    private final SocialLimits limits;
    private final SocialProperties properties;
    private final MediaStorage storage;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public PostService(PostRepository posts, PostMediaRepository mediaRows, CommentRepository comments,
                       ButterflyRepository butterflies, PlaceService placeService, PlaceRepository places, ProfileRepository profiles,
                       ProfileService profileService, Relationships relationships, SocialAccess access,
                       SocialLimits limits, SocialProperties properties, MediaStorage storage, Clock clock) {
        this.posts = posts;
        this.mediaRows = mediaRows;
        this.comments = comments;
        this.butterflies = butterflies;
        this.placeService = placeService;
        this.places = places;
        this.profiles = profiles;
        this.profileService = profileService;
        this.relationships = relationships;
        this.access = access;
        this.limits = limits;
        this.properties = properties;
        this.storage = storage;
        this.clock = clock;
    }

    @Transactional
    public PostResponse create(long userId, PostRequest request) {
        access.requireMember(userId);
        if (profiles.findByUserId(userId).isEmpty()) throw new ConflictException(PROFILE_NEEDED);
        limits.post(userId);
        PostType type = parse(PostType.class, request.type(), "Choose Experience or Ask");
        Precision precision = parse(Precision.class, request.precision(), "Choose how much of the place to show");
        Audience audience = parse(Audience.class, request.audience(), "Choose who can see this");
        CommentPolicy commentPolicy = audience == Audience.ONLY_ME ? CommentPolicy.NOBODY
                : parse(CommentPolicy.class, request.commentPolicy(), "Choose who can add perspectives");
        City city = parseOptional(City.class, request.city(), "Choose a city from the list");
        Daypart daypart = parseOptional(Daypart.class, request.daypart(), "Choose a time of day from the list");
        String category = blank(request.category()) ? null : PlaceService.category(request.category());
        YearMonth month = month(request.experiencedMonth());
        List<String> mediaIds = request.mediaIds() == null ? List.of() : request.mediaIds();
        if (new HashSet<>(mediaIds).size() != mediaIds.size()) throw new InvalidRequestException("A photo was added twice");
        Place place = blank(request.placeId()) ? null : placeService.requirePlace(request.placeId());

        String body = PostPolicy.cleanBody(request.body());
        String tip = PostPolicy.cleanLine(request.tip());
        LocalDateTime now = now();
        PostPolicy.check(new PostPolicy.Draft(type, body, tip, precision, place != null, city, month, audience,
                mediaIds.size()), now).ifPresent(reason -> {
                    throw new InvalidRequestException(reason);
                });

        LocalDateTime visibleFrom = now.plus(PostDelay.delay(precision, properties.exactDelayMin(),
                properties.exactDelayMax(), random));
        Post post = new Post(userId, type, now, visibleFrom);
        post.setContent(body, tip, category, month == null ? null : month.atDay(1), daypart);
        post.setLocation(precision, place == null ? null : place.getId(),
                precision == Precision.AREA ? place.getAreaCell() : null, precision == Precision.CITY ? city : null);
        post.setSharing(audience, commentPolicy);
        posts.saveAndFlush(post);
        attach(userId, post, mediaIds);
        return respond(userId, List.of(post), now).get(0);
    }

    @Transactional(readOnly = true)
    public PostResponse get(long viewerId, String publicId) {
        access.requireMember(viewerId);
        LocalDateTime now = now();
        return respond(viewerId, List.of(requireVisible(viewerId, publicId, now)), now).get(0);
    }

    @Transactional
    public PostResponse edit(long userId, String publicId, PostEditRequest request) {
        access.requireMember(userId);
        Post post = requireOwn(userId, publicId);
        Audience audience = parse(Audience.class, request.audience(), "Choose who can see this");
        Precision precision = parse(Precision.class, request.precision(), "Choose how much of the place to show");
        if (!PostPolicy.isNarrowerOrSame(post.getAudience(), audience)
                || !PostPolicy.isNarrowerOrSame(post.getPrecision(), precision)) {
            throw new ConflictException(ONLY_NARROWER);
        }
        CommentPolicy commentPolicy = audience == Audience.ONLY_ME ? CommentPolicy.NOBODY
                : parse(CommentPolicy.class, request.commentPolicy(), "Choose who can add perspectives");
        Daypart daypart = parseOptional(Daypart.class, request.daypart(), "Choose a time of day from the list");
        String category = blank(request.category()) ? null : PlaceService.category(request.category());
        YearMonth month = month(request.experiencedMonth());
        City city = parseOptional(City.class, request.city(), "Choose a city from the list");

        Long placeId = null;
        String areaCell = null;
        City newCity = null;
        switch (precision) {
            case EXACT -> placeId = post.getPlaceId();
            case AREA -> {
                placeId = post.getPlaceId();
                if (post.getPrecision() == Precision.AREA) {
                    areaCell = post.getAreaCell();
                } else {
                    Place place = placeId == null ? null : places.findById(placeId).orElse(null);
                    if (place == null) throw new ConflictException(PLACE_GONE);
                    areaCell = place.getAreaCell();
                }
            }
            case CITY -> {
                newCity = city;
                if (post.getPrecision() == Precision.CITY && city != post.getCity()) throw new ConflictException(ONLY_NARROWER);
            }
            case NONE -> {
            }
        }
        if ((precision == Precision.EXACT || precision == Precision.AREA) && placeId == null) {
            throw new ConflictException(PLACE_GONE);
        }
        String body = PostPolicy.cleanBody(request.body());
        String tip = PostPolicy.cleanLine(request.tip());
        LocalDateTime now = now();
        PostPolicy.check(new PostPolicy.Draft(post.getType(), body, tip, precision, placeId != null, newCity, month,
                audience, 0), now).ifPresent(reason -> {
                    throw new InvalidRequestException(reason);
                });
        post.setContent(body, tip, category, month == null ? null : month.atDay(1), daypart);
        post.setLocation(precision, placeId, areaCell, newCity);
        post.setSharing(audience, commentPolicy);
        post.markEdited(now);
        posts.saveAndFlush(post);
        return respond(userId, List.of(post), now).get(0);
    }

    @Transactional
    public void delete(long userId, String publicId) {
        access.requireMember(userId);
        Post post = requireOwn(userId, publicId);
        List<String> keys = mediaRows.findByPostIdOrderBySortOrderAsc(post.getId()).stream()
                .map(PostMedia::getStorageKey).toList();
        butterflies.deleteAllOfPost(post.getId());
        comments.deleteRepliesOfPost(post.getId());
        comments.deleteAllOfPost(post.getId());
        mediaRows.deleteAll(mediaRows.findByPostIdOrderBySortOrderAsc(post.getId()));
        posts.delete(post);
        posts.flush();
        deleteFilesAfterCommit(keys);
    }

    @Transactional(readOnly = true)
    public PostPage mine(long userId, String cursor) {
        access.requireMember(userId);
        LocalDateTime now = now();
        Post after = cursor(cursor, userId, userId, now);
        List<Post> page = posts.ownPage(userId, after == null ? END : after.getCreatedAt(),
                after == null ? Long.MAX_VALUE : after.getId(), PageRequest.of(0, PAGE_SIZE + 1));
        return page(userId, page, now);
    }

    /** Someone's posts the viewer may see; 404 when the viewer may not see the profile at all. */
    @Transactional(readOnly = true)
    public PostPage byProfile(long viewerId, String profilePublicId, String cursor) {
        access.requireMember(viewerId);
        long ownerId = profileService.requireVisible(viewerId, profilePublicId).getUserId();
        if (ownerId == viewerId) return mine(viewerId, cursor);
        LocalDateTime now = now();
        Set<Audience> audiences = Visibility.visibleAudiences(relationships.facts(viewerId, ownerId),
                relationships.connected(viewerId, ownerId));
        if (audiences.isEmpty()) return new PostPage(List.of(), null);
        Post after = cursor(cursor, viewerId, ownerId, now);
        List<Post> page = posts.visiblePage(ownerId, audiences, now, after == null ? END : after.getCreatedAt(),
                after == null ? Long.MAX_VALUE : after.getId(), PageRequest.of(0, PAGE_SIZE + 1));
        return page(viewerId, page, now);
    }

    /** For CommentService and MediaService: the post if [viewerId] may see it, else 404. */
    public Post requireVisible(long viewerId, String publicId, LocalDateTime now) {
        Post post = publicId == null ? null : posts.findByPublicId(publicId).orElse(null);
        if (post == null || !relationships.canSeePost(viewerId, post, now)) throw new ResourceNotFoundException(NOT_FOUND);
        return post;
    }

    // ---- helpers ----

    private Post requireOwn(long userId, String publicId) {
        Post post = publicId == null ? null : posts.findByPublicId(publicId).orElse(null);
        if (post == null || post.getAuthorId() != userId) throw new ResourceNotFoundException(NOT_FOUND);
        return post;
    }

    /** The post a cursor names; it must be by [authorId] and visible to the viewer (else the cursor is invalid). */
    private Post cursor(String cursor, long viewerId, long authorId, LocalDateTime now) {
        if (blank(cursor)) return null;
        Post post = posts.findByPublicId(cursor).orElse(null);
        if (post == null || post.getAuthorId() != authorId || !relationships.canSeePost(viewerId, post, now)) {
            throw new InvalidRequestException("Invalid cursor");
        }
        return post;
    }

    private PostPage page(long viewerId, List<Post> page, LocalDateTime now) {
        boolean more = page.size() > PAGE_SIZE;
        List<Post> items = more ? page.subList(0, PAGE_SIZE) : page;
        return new PostPage(respond(viewerId, items, now), more ? items.get(items.size() - 1).getPublicId() : null);
    }

    private void attach(long userId, Post post, List<String> mediaIds) {
        if (mediaIds.isEmpty()) return;
        Map<String, PostMedia> found = mediaRows.findForAttach(mediaIds).stream()
                .collect(Collectors.toMap(PostMedia::getPublicId, Function.identity()));
        for (int i = 0; i < mediaIds.size(); i++) {
            PostMedia media = found.get(mediaIds.get(i));
            if (media == null || media.getOwnerId() != userId || media.getPostId() != null) {
                throw new ResourceNotFoundException(MEDIA_NOT_FOUND);
            }
            media.attach(post.getId(), i);
        }
        mediaRows.saveAllAndFlush(found.values());
    }

    /**
     * Builds the answers for [list] as [viewerId] may see them (batch loads of author, places, photos and butterflies).
     * The caller has already checked that the viewer may see every post in [list] (Visibility).
     */
    public List<PostResponse> respond(long viewerId, List<Post> list, LocalDateTime now) {
        if (list.isEmpty()) return List.of();
        Set<Long> authorIds = list.stream().map(Post::getAuthorId).collect(Collectors.toSet());
        Map<Long, Profile> authors = profiles.findByUserIdIn(List.copyOf(authorIds)).stream()
                .collect(Collectors.toMap(Profile::getUserId, Function.identity()));
        Set<Long> placeIds = list.stream().filter(p -> p.getPrecision() == Precision.EXACT && p.getPlaceId() != null)
                .map(Post::getPlaceId).collect(Collectors.toSet());
        Map<Long, Place> placeById = placeIds.isEmpty() ? Map.of() : places.findByIdIn(placeIds).stream()
                .collect(Collectors.toMap(Place::getId, Function.identity()));
        List<Long> postIds = list.stream().map(Post::getId).toList();
        Map<Long, List<PostMedia>> mediaByPost = mediaRows.findByPostIdIn(postIds)
                .stream().collect(Collectors.groupingBy(PostMedia::getPostId));
        // Phase 13C: the viewer's own Cocoon states, and (author only) how many people saved their Experiences.
        Map<Long, ButterflyState> yours = butterflies.findByUserIdAndPostIdIn(viewerId, postIds).stream()
                .collect(Collectors.toMap(Butterfly::getPostId, Butterfly::getState));
        List<Long> tallied = list.stream().filter(p -> showsTally(viewerId, p)).map(Post::getId).toList();
        Map<Long, Long> counts = new HashMap<>();
        if (!tallied.isEmpty()) {
            for (Object[] row : butterflies.countByPostIds(tallied)) counts.put((Long) row[0], (Long) row[1]);
        }
        List<PostResponse> out = new ArrayList<>(list.size());
        for (Post post : list) {
            boolean own = post.getAuthorId() == viewerId;
            Profile author = authors.get(post.getAuthorId());
            Place place = post.getPlaceId() == null ? null : placeById.get(post.getPlaceId());
            List<MediaRef> media = mediaByPost.getOrDefault(post.getId(), List.of()).stream()
                    .sorted((a, b) -> Integer.compare(a.getSortOrder(), b.getSortOrder()))
                    .map(m -> new MediaRef(m.getPublicId(), m.getWidth(), m.getHeight())).toList();
            boolean canComment = Visibility.canComment(relationships.postFacts(viewerId, post, now),
                    post.getCommentPolicy());
            LocalDateTime since = own ? post.getCreatedAt() : post.getVisibleFrom();
            out.add(new PostResponse(post.getPublicId(), post.getType().name(),
                    author == null ? null : ProfileService.summary(author), own, post.getBody(), post.getTip(),
                    post.getCategory(), post.getPrecision().name(),
                    post.getPrecision() == Precision.EXACT && place != null
                            ? new PostPlace(place.getPublicId(), place.getName()) : null,
                    post.getPrecision() == Precision.AREA ? post.getAreaCell() : null,
                    post.getPrecision() == Precision.CITY && post.getCity() != null ? post.getCity().name() : null,
                    post.getExperiencedMonth() == null ? null : YearMonth.from(post.getExperiencedMonth()).toString(),
                    post.getDaypart() == null ? null : post.getDaypart().name(),
                    TimeBands.band(since, now).name(), post.getEditedAt() != null, media,
                    own ? post.getAudience().name() : null, post.getCommentPolicy().name(), canComment,
                    own ? post.getCreatedAt().toInstant(ZoneOffset.UTC) : null,
                    own ? post.getVisibleFrom().toInstant(ZoneOffset.UTC) : null,
                    yours.containsKey(post.getId()) ? yours.get(post.getId()).name() : null,
                    showsTally(viewerId, post) ? ButterflyTally.of(counts.getOrDefault(post.getId(), 0L)) : null));
        }
        return out;
    }

    /** C2: only the author of an Experience shared with others learns anything about its butterflies. */
    private static boolean showsTally(long viewerId, Post post) {
        return post.getAuthorId() == viewerId && post.getType() == PostType.EXPERIENCE
                && post.getAudience() != Audience.ONLY_ME;
    }

    /** Photo files go only after the rows are gone for good; a file left behind is removed by the clean-up job. */
    private void deleteFilesAfterCommit(List<String> keys) {
        if (keys.isEmpty()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                for (String key : keys) {
                    try {
                        storage.delete(key);
                    } catch (RuntimeException e) {
                        log.warn("A photo file could not be removed ({}); the clean-up job will retry",
                                e.getClass().getSimpleName());
                    }
                }
            }
        });
    }

    private static YearMonth month(String raw) {
        if (blank(raw)) return null;
        try {
            return YearMonth.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new InvalidRequestException("Give the month as YYYY-MM");
        }
    }

    static <E extends Enum<E>> E parse(Class<E> type, String raw, String message) {
        E value = parseOptional(type, raw, message);
        if (value == null) throw new InvalidRequestException(message);
        return value;
    }

    static <E extends Enum<E>> E parseOptional(Class<E> type, String raw, String message) {
        if (blank(raw)) return null;
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException(message);
        }
    }

    private static boolean blank(String text) {
        return text == null || text.isBlank();
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
}
