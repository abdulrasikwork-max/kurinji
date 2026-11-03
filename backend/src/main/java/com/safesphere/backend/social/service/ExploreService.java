package com.safesphere.backend.social.service;

import com.safesphere.backend.common.exception.InvalidRequestException;
import com.safesphere.backend.social.dto.ExploreDtos.ExploreHit;
import com.safesphere.backend.social.dto.ExploreDtos.ExplorePlace;
import com.safesphere.backend.social.dto.ExploreDtos.ExploreRequest;
import com.safesphere.backend.social.dto.ExploreDtos.ExploreResponse;
import com.safesphere.backend.social.entity.Place;
import com.safesphere.backend.social.entity.Post;
import com.safesphere.backend.social.entity.PostEnums.Daypart;
import com.safesphere.backend.social.entity.PostFacet;
import com.safesphere.backend.social.policy.ExploreFacets;
import com.safesphere.backend.social.policy.ExplorePolicy;
import com.safesphere.backend.social.policy.ExplorePolicy.Candidate;
import com.safesphere.backend.social.policy.ExplorePolicy.Hit;
import com.safesphere.backend.social.policy.ExplorePolicy.Query;
import com.safesphere.backend.social.repository.PlaceRepository;
import com.safesphere.backend.social.repository.PostFacetRepository;
import com.safesphere.backend.social.repository.PostRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 17G-1: Explore. Finds PLACES in one city from the Experiences the viewer may read, for a structured request
 * (ExplorePolicy decides which and in what order). The words never come here: the phone understood them.
 *
 * <p>Privacy and visibility, in the order the code applies them: (1) the viewer must be a member; (2) only Everyone-audience
 * EXPERIENCES that name an EXACT place in the city, not the viewer's own, already visible (Q1 delay) are candidates (an
 * AREA post promises not to reveal its place); (3) each candidate passes the Edition rule (Relationships.forEdition: blocks,
 * mutes, hold/removal, collapse); (4) only then are tags and times read, and only from those posts; (5) nothing returned
 * counts posts, people or reactions. The newest 400 visible posts form the window: a bound on work, not a ranking.
 */
@Service
public class ExploreService {

    /** How many of the newest visible experiences the engine looks at. */
    static final int CANDIDATES = 400;
    static final String NOTHING_ASKED = "Choose what you are looking for";
    static final String TOO_MANY_CATEGORIES = "Choose at most 2 categories";

    private final PostRepository posts;
    private final PostFacetRepository postFacets;
    private final PlaceRepository places;
    private final Relationships relationships;
    private final SocialAccess access;
    private final SocialLimits limits;
    private final Clock clock;

    public ExploreService(PostRepository posts, PostFacetRepository postFacets, PlaceRepository places,
                          Relationships relationships, SocialAccess access, SocialLimits limits, Clock clock) {
        this.posts = posts;
        this.postFacets = postFacets;
        this.places = places;
        this.relationships = relationships;
        this.access = access;
        this.limits = limits;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ExploreResponse search(long viewerId, ExploreRequest request) {
        access.requireMember(viewerId);
        Query query = query(request);
        limits.explore(viewerId);
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);

        Relationships.Viewer viewer = relationships.viewer(viewerId);
        List<Post> visible = relationships.forEdition(viewer,
                posts.exploreCandidates(query.city(), viewerId, now, PageRequest.of(0, CANDIDATES)), now);
        if (visible.isEmpty()) return new ExploreResponse(query.city(), query.requested(), List.of());

        Map<Long, List<PostFacet>> tags = postFacets.findByPostIdIn(visible.stream().map(Post::getId).toList()).stream()
                .collect(Collectors.groupingBy(PostFacet::getPostId));
        Map<Long, List<Post>> byPlace = new LinkedHashMap<>();
        for (Post post : visible) byPlace.computeIfAbsent(post.getPlaceId(), id -> new ArrayList<>()).add(post);
        Map<Long, Place> placeById = places.findByIdIn(byPlace.keySet()).stream()
                .collect(Collectors.toMap(Place::getId, p -> p));

        List<Candidate> candidates = new ArrayList<>();
        for (Map.Entry<Long, List<Post>> entry : byPlace.entrySet()) {
            Place place = placeById.get(entry.getKey());
            if (place == null || place.getMergedInto() != null) continue;
            Set<String> facets = new HashSet<>();
            Set<String> dayparts = new HashSet<>();
            LocalDateTime newest = null;
            for (Post post : entry.getValue()) {
                for (PostFacet tag : tags.getOrDefault(post.getId(), List.of())) facets.add(tag.getFacet());
                if (post.getDaypart() != null) dayparts.add(post.getDaypart().name());
                if (newest == null || post.getVisibleFrom().isAfter(newest)) newest = post.getVisibleFrom();
            }
            candidates.add(new Candidate(place.getId(), place.getCategory(), facets, dayparts, newest,
                    place.getNormalizedName()));
        }

        List<ExploreHit> hits = new ArrayList<>();
        for (Hit hit : ExplorePolicy.rank(query, candidates)) {
            Place place = placeById.get(hit.place().placeId());
            hits.add(new ExploreHit(new ExplorePlace(place.getPublicId(), place.getName(), place.getCategory(),
                    place.getCity()), hit.matchedCategory(), hit.matchedFacets(), hit.matchedDaypart(), hit.requested(),
                    hit.matched()));
        }
        return new ExploreResponse(query.city(), query.requested(), hits);
    }

    /** The request as a checked structured query: closed lists only, something asked for, at most two categories. */
    static Query query(ExploreRequest request) {
        String city = PlaceService.editionCity(request.city(), false);
        Set<String> categories = new LinkedHashSet<>();
        if (request.categories() != null) {
            for (String raw : request.categories()) categories.add(PlaceService.category(raw));
        }
        if (categories.size() > 2) throw new InvalidRequestException(TOO_MANY_CATEGORIES);
        ExploreFacets.Checked facets = ExploreFacets.check(request.facets(), ExploreFacets.MAX_PER_QUERY);
        if (!facets.ok()) throw new InvalidRequestException(facets.problem());
        Daypart daypart = PostService.parseOptional(Daypart.class, request.daypart(), "Choose a time of day from the list");
        Query query = new Query(city, Set.copyOf(categories), Set.copyOf(facets.codes()),
                daypart == null ? null : daypart.name());
        if (query.requested() == 0) throw new InvalidRequestException(NOTHING_ASKED);
        return query;
    }
}
