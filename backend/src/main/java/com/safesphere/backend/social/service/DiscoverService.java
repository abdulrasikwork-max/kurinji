package com.safesphere.backend.social.service;

import com.safesphere.backend.common.exception.InvalidRequestException;
import com.safesphere.backend.social.dto.DiscoverDtos.DiscoverItem;
import com.safesphere.backend.social.dto.DiscoverDtos.DiscoverPage;
import com.safesphere.backend.social.dto.DiscoverDtos.Edition;
import com.safesphere.backend.social.dto.DiscoverDtos.EditionSection;
import com.safesphere.backend.social.dto.DiscoverDtos.PlacePage;
import com.safesphere.backend.social.dto.PostDtos.PostPage;
import com.safesphere.backend.social.dto.PostDtos.PostResponse;
import com.safesphere.backend.social.entity.Place;
import com.safesphere.backend.social.entity.Post;
import com.safesphere.backend.social.entity.PostEnums.Audience;
import com.safesphere.backend.social.entity.PostEnums.City;
import com.safesphere.backend.social.entity.PostEnums.PostType;
import com.safesphere.backend.social.entity.PostEnums.Precision;
import com.safesphere.backend.social.policy.EditionPolicy;
import com.safesphere.backend.social.policy.EditionPolicy.Candidate;
import com.safesphere.backend.social.policy.EditionPolicy.Picked;
import com.safesphere.backend.social.policy.EditionPolicy.Reason;
import com.safesphere.backend.social.policy.EditionPolicy.Section;
import com.safesphere.backend.social.policy.ProfileLists;
import com.safesphere.backend.social.repository.CommentRepository;
import com.safesphere.backend.social.repository.PostRepository;
import com.safesphere.backend.social.repository.ProfileRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 13C: Discover (EXPERIENCE_PLATFORM_DESIGN.md §3.7). Every list is finite and ends; more only on an explicit
 * request (a cursor). Each list is a database pre-filter, then every post passes the one Visibility rule plus the mute
 * rule (Relationships.forLists) before it is shown. Place pages, category pages and the Edition hold only Everyone posts
 * (the Everyone promise); "From your connections" also holds Connections posts of the viewer's connections.
 */
@Service
public class DiscoverService {

    static final int PAGE_SIZE = 20;
    /** How many newest candidates an Edition section looks at before ordering (bounded work per request). */
    static final int CANDIDATES = 200;

    private static final LocalDateTime END = LocalDateTime.of(9999, 12, 31, 0, 0);

    private final PostRepository posts;
    private final CommentRepository comments;
    private final ProfileRepository profiles;
    private final PostService postService;
    private final PlaceService placeService;
    private final Relationships relationships;
    private final SocialAccess access;
    private final SocialLimits limits;
    private final Clock clock;

    public DiscoverService(PostRepository posts, CommentRepository comments, ProfileRepository profiles,
                           PostService postService, PlaceService placeService, Relationships relationships,
                           SocialAccess access, SocialLimits limits, Clock clock) {
        this.posts = posts;
        this.comments = comments;
        this.profiles = profiles;
        this.postService = postService;
        this.placeService = placeService;
        this.relationships = relationships;
        this.access = access;
        this.limits = limits;
        this.clock = clock;
    }

    /** C7: the weekly Edition for [cityCode]: In the city, Asks you could answer, the theme of the week. */
    @Transactional(readOnly = true)
    public Edition edition(long viewerId, String cityCode) {
        access.requireMember(viewerId);
        City city = PlaceService.editionCity(cityCode, false);
        limits.discover(viewerId);
        Instant instant = clock.instant();
        LocalDateTime now = now(instant);
        LocalDate monday = EditionPolicy.weekOf(instant);
        LocalDateTime cityFrom = EditionPolicy.startUtc(monday.minusWeeks(1));
        ProfileLists.Interest theme = EditionPolicy.theme(monday);
        Relationships.Viewer viewer = relationships.viewer(viewerId);
        Set<String> interests = interests(viewerId);
        PageRequest candidates = PageRequest.of(0, CANDIDATES);

        List<EditionSection> sections = new ArrayList<>();
        List<Post> cityPosts = relationships.forLists(viewer,
                posts.editionCandidates(PostType.EXPERIENCE, city, viewerId, cityFrom, now, candidates), now);
        List<Picked<Post>> cityPicked = EditionPolicy.pick(Section.CITY, candidates(cityPosts, interests),
                EditionPolicy.CITY_CAP);
        add(sections, Section.CITY, viewerId, cityPicked, now);

        List<Post> asks = relationships.forLists(viewer,
                posts.openAsks(city, viewerId, now.minus(EditionPolicy.ASKS_WINDOW), now, candidates), now);
        add(sections, Section.ASKS, viewerId,
                EditionPolicy.pick(Section.ASKS, candidates(asks, interests), EditionPolicy.ASKS_CAP), now);

        Set<Long> shown = new HashSet<>();
        for (Picked<Post> p : cityPicked) shown.add(p.item().getId());
        List<Post> themed = relationships.forLists(viewer, posts.themeCandidates(PostType.EXPERIENCE, city, viewerId,
                theme.name(), now.minus(EditionPolicy.THEME_WINDOW), now, candidates), now).stream()
                .filter(p -> !shown.contains(p.getId())).toList();
        add(sections, Section.THEME, viewerId,
                EditionPolicy.pick(Section.THEME, candidates(themed, interests), EditionPolicy.THEME_CAP), now);

        return new Edition(city.name(), monday.toString(), theme.name(), sections);
    }

    /** C9: connections' posts from the last 30 days, newest first, 20 per page; ends. */
    @Transactional(readOnly = true)
    public DiscoverPage connections(long viewerId, String cursor) {
        access.requireMember(viewerId);
        limits.discover(viewerId);
        LocalDateTime now = now(clock.instant());
        Relationships.Viewer viewer = relationships.viewer(viewerId);
        if (viewer.connected().isEmpty()) {
            if (cursor != null && !cursor.isBlank()) throw new InvalidRequestException("Invalid cursor");
            return new DiscoverPage(List.of(), null);
        }
        Post after = cursor(viewerId, cursor, now);
        List<Post> raw = posts.connectionsPage(viewer.connected(), EnumSet.of(Audience.EVERYONE, Audience.CONNECTIONS),
                now.minus(EditionPolicy.CONNECTIONS_WINDOW), now, after == null ? END : after.getVisibleFrom(),
                after == null ? Long.MAX_VALUE : after.getId(), PageRequest.of(0, PAGE_SIZE + 1));
        Page page = page(viewer, raw, now);
        List<DiscoverItem> items = postService.respond(viewerId, page.shown(), now).stream()
                .map(p -> new DiscoverItem(p, List.of(Reason.FROM_A_CONNECTION.name()))).toList();
        return new DiscoverPage(items, page.nextCursor());
    }

    /** C10: a place and its exact-place Everyone posts. A merged place shows the place it was merged into. */
    @Transactional(readOnly = true)
    public PlacePage place(long viewerId, String placeId, String cursor) {
        access.requireMember(viewerId);
        Place place = placeService.requirePlace(placeId);
        limits.discover(viewerId);
        LocalDateTime now = now(clock.instant());
        Relationships.Viewer viewer = relationships.viewer(viewerId);
        Post after = cursor(viewerId, cursor, now);
        if (after != null && (after.getPlaceId() == null || !after.getPlaceId().equals(place.getId()))) {
            throw new InvalidRequestException("Invalid cursor");
        }
        List<Post> raw = posts.placePage(place.getId(), now, after == null ? END : after.getVisibleFrom(),
                after == null ? Long.MAX_VALUE : after.getId(), PageRequest.of(0, PAGE_SIZE + 1));
        Page page = page(viewer, raw, now);
        return new PlacePage(PlaceService.toResponse(place), postService.respond(viewerId, page.shown(), now),
                page.nextCursor());
    }

    /** C11: Everyone posts of one category, optionally in one city. */
    @Transactional(readOnly = true)
    public PostPage category(long viewerId, String categoryCode, String cityCode, String cursor) {
        access.requireMember(viewerId);
        String category = PlaceService.category(categoryCode);
        City city = PlaceService.editionCity(cityCode, true);
        limits.discover(viewerId);
        LocalDateTime now = now(clock.instant());
        Relationships.Viewer viewer = relationships.viewer(viewerId);
        Post after = cursor(viewerId, cursor, now);
        if (after != null && !category.equals(after.getCategory())) throw new InvalidRequestException("Invalid cursor");
        LocalDateTime cursorAt = after == null ? END : after.getVisibleFrom();
        long cursorId = after == null ? Long.MAX_VALUE : after.getId();
        PageRequest request = PageRequest.of(0, PAGE_SIZE + 1);
        List<Post> raw = city == null ? posts.categoryPage(category, now, cursorAt, cursorId, request)
                : posts.categoryCityPage(category, city, now, cursorAt, cursorId, request);
        Page page = page(viewer, raw, now);
        return new PostPage(postService.respond(viewerId, page.shown(), now), page.nextCursor());
    }

    // ---- helpers ----

    private record Page(List<Post> shown, String nextCursor) {
    }

    /**
     * One page from [raw] (up to PAGE_SIZE + 1 rows): the rows of this page that pass the one rule and the mute rule,
     * and a cursor when more rows exist. A page can be shorter than PAGE_SIZE when some rows may not be shown.
     */
    private Page page(Relationships.Viewer viewer, List<Post> raw, LocalDateTime now) {
        boolean more = raw.size() > PAGE_SIZE;
        List<Post> rows = more ? raw.subList(0, PAGE_SIZE) : raw;
        return new Page(relationships.forLists(viewer, rows, now),
                more ? rows.get(rows.size() - 1).getPublicId() : null);
    }

    /** The post a cursor names; it must be visible to the viewer (else the cursor is invalid). */
    private Post cursor(long viewerId, String cursor, LocalDateTime now) {
        if (cursor == null || cursor.isBlank()) return null;
        Post post = posts.findByPublicId(cursor).orElse(null);
        if (post == null || !relationships.canSeePost(viewerId, post, now)) {
            throw new InvalidRequestException("Invalid cursor");
        }
        return post;
    }

    private void add(List<EditionSection> sections, Section section, long viewerId, List<Picked<Post>> picked,
                     LocalDateTime now) {
        if (picked.isEmpty()) return;
        List<PostResponse> answers = postService.respond(viewerId, picked.stream().map(Picked::item).toList(), now);
        List<DiscoverItem> items = new ArrayList<>(answers.size());
        for (int i = 0; i < answers.size(); i++) {
            items.add(new DiscoverItem(answers.get(i),
                    picked.get(i).reasons().stream().map(Enum::name).toList()));
        }
        sections.add(new EditionSection(section.name(), items));
    }

    private List<Candidate<Post>> candidates(List<Post> list, Set<String> interests) {
        if (list.isEmpty()) return List.of();
        Set<Long> beenHere = new HashSet<>(comments.postsWithBeenHere(list.stream().map(Post::getId).toList()));
        List<Candidate<Post>> out = new ArrayList<>(list.size());
        for (Post p : list) {
            boolean hasPlace = p.getPrecision() == Precision.EXACT || p.getPrecision() == Precision.AREA;
            boolean hasWhen = p.getExperiencedMonth() != null && p.getDaypart() != null;
            boolean hasTip = p.getTip() != null && !p.getTip().isBlank();
            boolean interest = p.getCategory() != null && interests.contains(p.getCategory());
            out.add(new Candidate<>(p, hasPlace, hasWhen, hasTip, interest, beenHere.contains(p.getId()),
                    p.getVisibleFrom(), p.getId()));
        }
        return out;
    }

    private Set<String> interests(long viewerId) {
        return profiles.findByUserId(viewerId).map(p -> (Set<String>) new HashSet<>(ProfileLists.decode(p.getInterests())))
                .orElse(Set.of());
    }

    private static LocalDateTime now(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
}
