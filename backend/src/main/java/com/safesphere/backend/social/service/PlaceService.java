package com.safesphere.backend.social.service;

import com.safesphere.backend.common.exception.InvalidRequestException;
import com.safesphere.backend.common.exception.ResourceNotFoundException;
import com.safesphere.backend.community.service.GeoCell;
import com.safesphere.backend.social.dto.PlaceDtos.PlaceRequest;
import com.safesphere.backend.social.dto.PlaceDtos.PlaceResponse;
import com.safesphere.backend.social.entity.Place;
import com.safesphere.backend.social.entity.PostEnums.City;
import com.safesphere.backend.social.policy.CityAreas;
import com.safesphere.backend.social.policy.PlaceNamePolicy;
import com.safesphere.backend.social.policy.ProfileLists;
import com.safesphere.backend.social.repository.PlaceRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 13B: places people write about (design §3.5, D11: user-created, no places API). The phone sends only a
 * 7-character cell (~150 m) to create a place and a 6-character area to list places nearby; no coordinate ever arrives.
 * A place records no creator (Q12). The same name in the same cell is the same place: creating it again returns it.
 */
@Service
public class PlaceService {

    static final String NOT_FOUND = "Place not found";
    static final int NEARBY_MAX = 100;
    static final int SEARCH_MIN = 2;
    static final int SEARCH_MAX = 40;
    static final int SEARCH_RESULTS = 20;

    private final PlaceRepository places;
    private final SocialAccess access;
    private final SocialLimits limits;
    private final Clock clock;

    public PlaceService(PlaceRepository places, SocialAccess access, SocialLimits limits, Clock clock) {
        this.places = places;
        this.access = access;
        this.limits = limits;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public PlaceResponse create(long userId, PlaceRequest request) {
        access.requireMember(userId);
        limits.place(userId);
        String cell = request.cell().trim().toLowerCase(Locale.ROOT);
        if (!GeoCell.isValid(cell, GeoCell.BLOCK_PRECISION)) throw new InvalidRequestException("Invalid cell");
        String category = category(request.category());
        String name = PlaceNamePolicy.clean(request.name());
        switch (PlaceNamePolicy.check(name)) {
            case OK -> {
            }
            case TOO_SHORT -> throw new InvalidRequestException("A place name has at least 2 characters");
            case TOO_LONG -> throw new InvalidRequestException("A place name has at most 60 characters");
            case CONTAINS_LINK -> throw new InvalidRequestException("Links are not allowed in a place name");
            case NO_LETTERS -> throw new InvalidRequestException("A place name needs letters or digits");
            case PRIVATE_PLACE -> throw new InvalidRequestException(
                    "This looks like a private place. Share it as the city or with no place instead.");
        }
        String normalized = PlaceNamePolicy.normalize(name);
        Place existing = places.findByCellAndNormalizedName(cell, normalized).orElse(null);
        if (existing != null) return toResponse(resolve(existing));
        try {
            return toResponse(places.saveAndFlush(
                    new Place(name, normalized, category, cell, CityAreas.cityOf(cell).orElse(null),
                            LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC))));
        } catch (DataIntegrityViolationException e) {
            // Someone created the same place at the same moment: it exists, which is what was asked.
            return places.findByCellAndNormalizedName(cell, normalized).map(p -> toResponse(resolve(p)))
                    .orElseThrow(() -> e);
        }
    }

    @Transactional(readOnly = true)
    public List<PlaceResponse> nearby(long userId, String area) {
        access.requireMember(userId);
        limits.placeLookup(userId);
        String cell = area == null ? "" : area.trim().toLowerCase(Locale.ROOT);
        if (!GeoCell.isValid(cell, GeoCell.AREA_PRECISION)) throw new InvalidRequestException("Invalid area");
        return places.findByAreaCellInAndMergedIntoIsNullOrderByNameAsc(GeoCell.neighbourhood(cell),
                PageRequest.of(0, NEARBY_MAX)).stream().map(PlaceService::toResponse).toList();
    }

    /**
     * Phase 13C (C12): places whose name contains [query] (compared as normalised names: letters and digits, lower
     * case), optionally only in one launch city. Places only: there is no search for people or for post text.
     */
    @Transactional(readOnly = true)
    public List<PlaceResponse> search(long userId, String query, String city) {
        access.requireMember(userId);
        String cleaned = PlaceNamePolicy.clean(query == null ? "" : query);
        if (cleaned.length() < SEARCH_MIN || cleaned.length() > SEARCH_MAX) {
            throw new InvalidRequestException("Search with 2 to 40 characters");
        }
        String part = PlaceNamePolicy.normalize(cleaned);
        if (part.isEmpty()) throw new InvalidRequestException("Search with letters or digits");
        City inCity = editionCity(city, true);
        limits.search(userId);
        PageRequest page = PageRequest.of(0, SEARCH_RESULTS);
        List<Place> found = inCity == null
                ? places.findByNormalizedNameContainingAndMergedIntoIsNullOrderByNameAsc(part, page)
                : places.findByNormalizedNameContainingAndCityAndMergedIntoIsNullOrderByNameAsc(part, inCity, page);
        return found.stream().map(PlaceService::toResponse).toList();
    }

    /**
     * Phase 13C: a city an Edition or a list can be made for (the closed list without OTHER). Blank is allowed only
     * when [optional]; then null.
     */
    public static City editionCity(String raw, boolean optional) {
        if (raw == null || raw.isBlank()) {
            if (optional) return null;
            throw new InvalidRequestException("Choose a city from the list");
        }
        try {
            City city = City.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            if (CityAreas.hasArea(city)) return city;
        } catch (IllegalArgumentException e) {
            // falls through to the same answer as OTHER
        }
        throw new InvalidRequestException("Choose a city from the list");
    }

    @Transactional(readOnly = true)
    public PlaceResponse one(long userId, String publicId) {
        access.requireMember(userId);
        return toResponse(resolve(requirePlace(publicId)));
    }

    /** For PostService: the place a post may use (a merged place stands for the place it was merged into). */
    public Place requirePlace(String publicId) {
        Place place = publicId == null ? null : places.findByPublicId(publicId).orElse(null);
        if (place == null) throw new ResourceNotFoundException(NOT_FOUND);
        return resolve(place);
    }

    public static PlaceResponse toResponse(Place place) {
        return new PlaceResponse(place.getPublicId(), place.getName(), place.getCategory(), place.getCell(),
                place.getAreaCell(), place.getCity() == null ? null : place.getCity().name());
    }

    /** Follows merged_into (at most a few hops; a loop stops at the first repeat). */
    private Place resolve(Place place) {
        Place current = place;
        for (int hops = 0; hops < 5 && current.getMergedInto() != null; hops++) {
            Place next = places.findById(current.getMergedInto()).orElse(null);
            if (next == null || next.getId().equals(place.getId())) break;
            current = next;
        }
        return current;
    }

    static String category(String raw) {
        try {
            return ProfileLists.Interest.valueOf(raw.trim().toUpperCase(Locale.ROOT)).name();
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new InvalidRequestException("Choose a category from the list");
        }
    }
}
