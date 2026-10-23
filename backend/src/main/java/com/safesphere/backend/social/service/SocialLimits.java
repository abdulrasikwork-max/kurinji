package com.safesphere.backend.social.service;

import com.safesphere.backend.common.exception.TooManyAttemptsException;
import com.safesphere.backend.config.SocialProperties;
import com.safesphere.backend.security.AttemptLimiter;
import java.time.Clock;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Phase 13A: the in-memory per-person limits of the social features (same reasoning as Phase 7's limits: nothing about
 * who looked up whom is stored). Per instance; reset on restart (documented limitation).
 */
@Component
public class SocialLimits {

    static final String TOO_MANY_LOOKUPS = "Too many profile look-ups. Please try again later.";
    static final String TOO_MANY_REQUESTS = "You have sent many connection requests today. Please try again tomorrow.";
    static final String TOO_MANY_INVITES = "You have created many invite codes today. Please try again tomorrow.";
    static final String TOO_MANY_REDEEMS = "Too many invite codes tried. Please try again later.";
    static final String TOO_MANY_PLACES = "You have added many places today. Please try again tomorrow.";
    static final String TOO_MANY_PLACE_LOOKUPS = "Too many place look-ups. Please try again later.";
    static final String TOO_MANY_POSTS = "You have shared a lot today. Please try again tomorrow.";
    static final String TOO_MANY_PHOTOS = "You have added many photos today. Please try again tomorrow.";
    static final String TOO_MANY_COMMENTS = "You have written many perspectives today. Please try again tomorrow.";

    private final AttemptLimiter lookups;
    private final AttemptLimiter requests;
    private final AttemptLimiter invites;
    private final AttemptLimiter redeems;
    private final AttemptLimiter places;
    private final AttemptLimiter placeLookups;
    private final AttemptLimiter posts;
    private final AttemptLimiter media;
    private final AttemptLimiter comments;

    public SocialLimits(SocialProperties properties, Clock clock) {
        int keys = properties.maxTrackedKeys();
        this.lookups = new AttemptLimiter(properties.lookupsPerHour(), Duration.ofHours(1), keys, clock);
        this.requests = new AttemptLimiter(properties.requestsPerDay(), Duration.ofDays(1), keys, clock);
        this.invites = new AttemptLimiter(properties.invitesPerDay(), Duration.ofDays(1), keys, clock);
        this.redeems = new AttemptLimiter(properties.redeemsPerHour(), Duration.ofHours(1), keys, clock);
        this.places = new AttemptLimiter(properties.placesPerDay(), Duration.ofDays(1), keys, clock);
        this.placeLookups = new AttemptLimiter(properties.placeLookupsPerHour(), Duration.ofHours(1), keys, clock);
        this.posts = new AttemptLimiter(properties.postsPerDay(), Duration.ofDays(1), keys, clock);
        this.media = new AttemptLimiter(properties.mediaPerDay(), Duration.ofDays(1), keys, clock);
        this.comments = new AttemptLimiter(properties.commentsPerDay(), Duration.ofDays(1), keys, clock);
    }

    public void lookup(long userId) {
        if (!lookups.tryAcquire(key(userId))) throw new TooManyAttemptsException(TOO_MANY_LOOKUPS);
    }

    public void request(long userId) {
        if (!requests.tryAcquire(key(userId))) throw new TooManyAttemptsException(TOO_MANY_REQUESTS);
    }

    public void invite(long userId) {
        if (!invites.tryAcquire(key(userId))) throw new TooManyAttemptsException(TOO_MANY_INVITES);
    }

    public void redeem(long userId) {
        if (!redeems.tryAcquire(key(userId))) throw new TooManyAttemptsException(TOO_MANY_REDEEMS);
    }

    public void place(long userId) {
        if (!places.tryAcquire(key(userId))) throw new TooManyAttemptsException(TOO_MANY_PLACES);
    }

    public void placeLookup(long userId) {
        if (!placeLookups.tryAcquire(key(userId))) throw new TooManyAttemptsException(TOO_MANY_PLACE_LOOKUPS);
    }

    public void post(long userId) {
        if (!posts.tryAcquire(key(userId))) throw new TooManyAttemptsException(TOO_MANY_POSTS);
    }

    public void media(long userId) {
        if (!media.tryAcquire(key(userId))) throw new TooManyAttemptsException(TOO_MANY_PHOTOS);
    }

    public void comment(long userId) {
        if (!comments.tryAcquire(key(userId))) throw new TooManyAttemptsException(TOO_MANY_COMMENTS);
    }

    private static String key(long userId) {
        return "user:" + userId;
    }
}
