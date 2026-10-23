package com.safesphere.backend.social.service;

import com.safesphere.backend.common.exception.ConflictException;
import com.safesphere.backend.common.exception.InvalidRequestException;
import com.safesphere.backend.common.exception.ResourceNotFoundException;
import com.safesphere.backend.config.SocialProperties;
import com.safesphere.backend.social.dto.ProfileDtos.OwnProfileResponse;
import com.safesphere.backend.social.dto.ProfileDtos.PersonProfileResponse;
import com.safesphere.backend.social.dto.ProfileDtos.PersonSummary;
import com.safesphere.backend.social.dto.ProfileDtos.ProfileRequest;
import com.safesphere.backend.social.entity.Profile;
import com.safesphere.backend.social.policy.HandlePolicy;
import com.safesphere.backend.social.policy.ProfileLists;
import com.safesphere.backend.social.policy.ProfileTextPolicy;
import com.safesphere.backend.social.repository.ProfileRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 13A: profiles. Creating or editing one needs an adult-confirmed account; a handle is unique, can change once per
 * 30 days, and the handle someone left stays reserved for 30 days. Looking someone up is by exact handle (or public id)
 * only, rate-limited, and answered through the one Visibility rule: a profile the viewer may not see is "not found",
 * exactly like one that does not exist.
 */
@Service
public class ProfileService {

    static final String HANDLE_INVALID =
            "A handle has 3 to 20 lower-case letters, digits, '_' or '.', and starts with a letter";
    static final String HANDLE_UNAVAILABLE = "That handle is not available";
    static final String HANDLE_COOLDOWN = "You can change your handle once every 30 days";
    static final String NOT_FOUND = "No one with that handle";
    static final String NO_PROFILE = "You have not made a profile yet";

    private final ProfileRepository profiles;
    private final SocialAccess access;
    private final Relationships relationships;
    private final SocialLimits limits;
    private final SocialProperties properties;
    private final Clock clock;

    public ProfileService(ProfileRepository profiles, SocialAccess access, Relationships relationships,
                          SocialLimits limits, SocialProperties properties, Clock clock) {
        this.profiles = profiles;
        this.access = access;
        this.relationships = relationships;
        this.limits = limits;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public OwnProfileResponse own(long userId) {
        access.requireMember(userId);
        return toOwn(profiles.findByUserId(userId).orElseThrow(() -> new ResourceNotFoundException(NO_PROFILE)));
    }

    @Transactional
    public OwnProfileResponse save(long userId, ProfileRequest request) {
        access.requireMember(userId);
        String handle = HandlePolicy.normalize(request.handle());
        switch (HandlePolicy.check(handle)) {
            case OK -> {
            }
            case INVALID_FORMAT -> throw new InvalidRequestException(HANDLE_INVALID);
            case RESERVED -> throw new ConflictException(HANDLE_UNAVAILABLE);
        }
        String displayName = ProfileTextPolicy.clean(request.displayName());
        requireText(ProfileTextPolicy.checkDisplayName(displayName), "Enter a name of 1 to 40 characters");
        String bio = emptyToNull(ProfileTextPolicy.clean(request.bio()));
        requireText(ProfileTextPolicy.checkBio(bio), "Write a bio of at most 160 characters");
        String city = emptyToNull(ProfileTextPolicy.clean(request.city()));
        requireText(ProfileTextPolicy.checkCity(city), "Enter a city name of at most 60 letters");
        String languages;
        String interests;
        try {
            languages = ProfileLists.encodeLanguages(request.languages());
            interests = ProfileLists.encodeInterests(request.interests());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new InvalidRequestException("Choose languages and interests from the list");
        }

        LocalDateTime now = now();
        Profile profile = profiles.findByUserId(userId).orElse(null);
        if (profile == null) {
            requireAvailable(handle, userId, now);
            profile = new Profile(userId, handle, now);
        } else if (!profile.getHandle().equals(handle)) {
            LocalDateTime changed = profile.getHandleChangedAt();
            if (changed != null && now.isBefore(changed.plus(properties.handleChangeCooldown()))) {
                throw new ConflictException(HANDLE_COOLDOWN);
            }
            requireAvailable(handle, userId, now);
            profile.changeHandle(handle, now, now.plus(properties.handleReservation()));
        }
        profile.edit(displayName, bio, languages, interests, city, now);
        try {
            // saveAndFlush so a race on the unique handle fails here, as a clear 409.
            return toOwn(profiles.saveAndFlush(profile));
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException(HANDLE_UNAVAILABLE);
        }
    }

    @Transactional(readOnly = true)
    public PersonProfileResponse byHandle(long viewerId, String rawHandle) {
        access.requireMember(viewerId);
        limits.lookup(viewerId);
        String handle = HandlePolicy.normalize(rawHandle);
        if (HandlePolicy.check(handle) == HandlePolicy.Result.INVALID_FORMAT) throw new ResourceNotFoundException(NOT_FOUND);
        return visible(viewerId, profiles.findByHandle(handle).orElse(null));
    }

    @Transactional(readOnly = true)
    public PersonProfileResponse byPublicId(long viewerId, String publicId) {
        access.requireMember(viewerId);
        limits.lookup(viewerId);
        return visible(viewerId, profiles.findByPublicId(publicId).orElse(null));
    }

    /** The visible profile for [publicId], for other social services (requests, blocks). Not found = 404. */
    @Transactional(readOnly = true)
    public Profile requireVisible(long viewerId, String publicId) {
        Profile profile = profiles.findByPublicId(publicId).orElse(null);
        if (profile == null || !relationships.canSeeProfile(viewerId, profile.getUserId())) {
            throw new ResourceNotFoundException(NOT_FOUND);
        }
        return profile;
    }

    public static PersonSummary summary(Profile profile) {
        return new PersonSummary(profile.getPublicId(), profile.getHandle(), profile.getDisplayName());
    }

    private PersonProfileResponse visible(long viewerId, Profile profile) {
        if (profile == null || !relationships.canSeeProfile(viewerId, profile.getUserId())) {
            throw new ResourceNotFoundException(NOT_FOUND);
        }
        return new PersonProfileResponse(profile.getPublicId(), profile.getHandle(), profile.getDisplayName(),
                profile.getBio(), ProfileLists.decode(profile.getLanguages()), ProfileLists.decode(profile.getInterests()),
                profile.getCity(), relationships.relationship(viewerId, profile.getUserId()));
    }

    /**
     * Taken = someone else's current handle, or someone else's previous handle still reserved. A reservation whose time
     * is over is released here. Taking back one's own previous handle is allowed.
     */
    private void requireAvailable(String handle, long userId, LocalDateTime now) {
        if (profiles.findByHandle(handle).filter(p -> p.getUserId() != userId).isPresent()) {
            throw new ConflictException(HANDLE_UNAVAILABLE);
        }
        Profile reserving = profiles.findByPreviousHandle(handle).orElse(null);
        if (reserving != null && reserving.getUserId() != userId) {
            if (now.isBefore(reserving.getPreviousHandleUntil())) throw new ConflictException(HANDLE_UNAVAILABLE);
            reserving.releasePreviousHandle();
            profiles.saveAndFlush(reserving);
        }
    }

    private OwnProfileResponse toOwn(Profile profile) {
        LocalDateTime changed = profile.getHandleChangedAt();
        LocalDateTime availableAt = changed == null ? null : changed.plus(properties.handleChangeCooldown());
        boolean waiting = availableAt != null && now().isBefore(availableAt);
        return new OwnProfileResponse(profile.getPublicId(), profile.getHandle(), profile.getDisplayName(),
                profile.getBio(), ProfileLists.decode(profile.getLanguages()), ProfileLists.decode(profile.getInterests()),
                profile.getCity(), waiting ? availableAt.toInstant(ZoneOffset.UTC) : null);
    }

    private static void requireText(ProfileTextPolicy.Result result, String message) {
        switch (result) {
            case OK -> {
            }
            case CONTAINS_LINK -> throw new InvalidRequestException("Links are not allowed in a profile");
            default -> throw new InvalidRequestException(message);
        }
    }

    private static String emptyToNull(String text) {
        return text == null || text.isEmpty() ? null : text;
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
}
