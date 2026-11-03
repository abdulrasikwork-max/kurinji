package com.safesphere.backend.social.service;

import com.safesphere.backend.common.exception.ConflictException;
import com.safesphere.backend.common.exception.InvalidRequestException;
import com.safesphere.backend.common.exception.ResourceNotFoundException;
import com.safesphere.backend.social.dto.PlaceDtos.VisitResponse;
import com.safesphere.backend.social.entity.Place;
import com.safesphere.backend.social.entity.PlaceVisit;
import com.safesphere.backend.social.entity.Post;
import com.safesphere.backend.social.policy.RatingPolicy;
import com.safesphere.backend.social.repository.PlaceVisitRepository;
import com.safesphere.backend.social.repository.PostRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 17G-2 (U5): "I went" and the optional rating. Everything here is about the CALLER's own row; no method returns another
 * person's visit or rating (the only public number is PlaceRatings' guarded average).
 *
 * <p>Rules: a person may mark "went" only for a place they can already SEE (a post at the place they may read through the Edition
 * rule, or one they wrote there), so the endpoint cannot be used to probe places that are hidden from them; a rating needs the
 * visit first (by construction one row); a person paused from posting cannot rate (M9); a rating is 1 to 5; two daily limits bound
 * marks and rating writes. Writes are idempotent and are not in a transaction of their own: a double tap that loses the unique-key
 * race finds the other row (never a caught unique-key error inside a transaction, Phase 14). "I went" is self-declared.
 */
@Service
public class PlaceVisitService {

    static final String NOT_FOUND = "Place not found";
    static final String WENT_FIRST = "Mark that you went there first";
    static final String CHOOSE = "Choose 1 to 5";
    /** How many of a place's newest posts are looked at to decide that the caller can see the place. */
    static final int SEE_WINDOW = 20;

    private static final LocalDateTime END = LocalDateTime.of(9999, 12, 31, 0, 0);

    private final PlaceVisitRepository visits;
    private final PostRepository posts;
    private final PlaceService places;
    private final Relationships relationships;
    private final SocialAccess access;
    private final SocialLimits limits;
    private final Clock clock;

    public PlaceVisitService(PlaceVisitRepository visits, PostRepository posts, PlaceService places,
                             Relationships relationships, SocialAccess access, SocialLimits limits, Clock clock) {
        this.visits = visits;
        this.posts = posts;
        this.places = places;
        this.relationships = relationships;
        this.access = access;
        this.limits = limits;
        this.clock = clock;
    }

    /** "I went": the caller's private record for a place they can see (idempotent; keeps an earlier rating). */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public VisitResponse went(long userId, String placePublicId) {
        access.requireMember(userId);
        Place place = places.requirePlace(placePublicId);
        if (!canSee(userId, place)) throw new ResourceNotFoundException(NOT_FOUND);
        PlaceVisit existing = visits.findByUserIdAndPlaceId(userId, place.getId()).orElse(null);
        if (existing != null) return view(existing);
        limits.visit(userId);
        try {
            return view(visits.saveAndFlush(new PlaceVisit(userId, place.getId(), now())));
        } catch (DataIntegrityViolationException e) {
            // A double tap: the other request stored it, which is what was asked.
            return view(visits.findByUserIdAndPlaceId(userId, place.getId()).orElseThrow(() -> e));
        }
    }

    /** Takes "I went" back, and the rating with it. The same answer whether there was a visit or not. */
    @Transactional
    public void notWent(long userId, String placePublicId) {
        access.requireMember(userId);
        Place place = placePublicId == null ? null : places.findForVisit(placePublicId);
        if (place == null) return;
        visits.findByUserIdAndPlaceId(userId, place.getId()).ifPresent(visits::delete);
    }

    /** The caller's rating (1 to 5) for a place they went to. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public VisitResponse rate(long userId, String placePublicId, Integer rating) {
        access.requireCanPost(userId); // a pause from posting also stops ratings (they feed a public number)
        if (!RatingPolicy.isValid(rating)) throw new InvalidRequestException(CHOOSE);
        Place place = places.requirePlace(placePublicId);
        PlaceVisit visit = visits.findByUserIdAndPlaceId(userId, place.getId())
                .orElseThrow(() -> new ConflictException(WENT_FIRST));
        limits.ratingWrite(userId);
        visit.rate(rating, now());
        return view(visits.saveAndFlush(visit));
    }

    /** Takes the rating back and keeps "I went". */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public VisitResponse clearRating(long userId, String placePublicId) {
        access.requireMember(userId);
        Place place = places.requirePlace(placePublicId);
        PlaceVisit visit = visits.findByUserIdAndPlaceId(userId, place.getId())
                .orElseThrow(() -> new ConflictException(WENT_FIRST));
        limits.ratingWrite(userId);
        visit.clearRating();
        return view(visits.saveAndFlush(visit));
    }

    /** The caller's own visit for the place page, or null. */
    @Transactional(readOnly = true)
    public VisitResponse yourVisit(long userId, long placeId) {
        return visits.findByUserIdAndPlaceId(userId, placeId).map(PlaceVisitService::view).orElse(null);
    }

    /**
     * A merged-away duplicate: its visits move to the place it was merged into. A person who has a row at both keeps ONE: the
     * rated one (the row already at the surviving place wins a tie). Done in code, not in one bulk DELETE: MySQL refuses a
     * DELETE that reads the table it deletes from.
     */
    @Transactional
    public void mergePlace(long fromId, long intoId) {
        for (PlaceVisit moving : visits.findByPlaceId(fromId)) {
            PlaceVisit staying = visits.findByUserIdAndPlaceId(moving.getUserId(), intoId).orElse(null);
            if (staying == null) {
                moving.moveTo(intoId);
                visits.save(moving);
            } else if (staying.getRating() == null && moving.getRating() != null) {
                visits.delete(staying);
                visits.flush();
                moving.moveTo(intoId);
                visits.save(moving);
            } else {
                visits.delete(moving);
            }
            visits.flush();
        }
    }

    /** The caller can see the place: they wrote a post there, or may read one there (the Edition rule). */
    private boolean canSee(long userId, Place place) {
        if (posts.existsByAuthorIdAndPlaceId(userId, place.getId())) return true;
        LocalDateTime now = now();
        List<Post> newest = posts.placePage(place.getId(), now, END, Long.MAX_VALUE, PageRequest.of(0, SEE_WINDOW));
        return !relationships.forEdition(relationships.viewer(userId), newest, now).isEmpty();
    }

    private static VisitResponse view(PlaceVisit visit) {
        return new VisitResponse(visit.getRating());
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
