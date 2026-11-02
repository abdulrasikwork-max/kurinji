package com.safesphere.backend.social.service;

import com.safesphere.backend.common.exception.InvalidRequestException;
import com.safesphere.backend.common.exception.ResourceNotFoundException;
import com.safesphere.backend.config.SocialProperties;
import com.safesphere.backend.social.entity.Profile;
import com.safesphere.backend.social.media.ImageSanitizer;
import com.safesphere.backend.social.media.MediaStorage;
import com.safesphere.backend.social.repository.ProfileRepository;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Phase 17E: profile photos. An upload is checked and re-encoded by ImageSanitizer (magic bytes, header size before
 * decoding, centred square, at most [SIDE] pixels, no metadata) and stored under a NEW random key, so a phone's memory
 * cache can never show an old photo. A photo is served only to someone who may see the profile (the one rule: a block
 * is 404, exactly like a profile that does not exist); the key alone is never enough. Files of replaced photos go after
 * the commit; a file whose row never committed is removed by the clean-up job.
 */
@Service
public class ProfilePhotoService {

    public static final int SIDE = 720;
    static final String NO_PROFILE = "Make your profile first";
    static final String NOT_FOUND = "No photo";

    private static final Logger log = LoggerFactory.getLogger(ProfilePhotoService.class);

    private final ProfileRepository profiles;
    private final Relationships relationships;
    private final SocialAccess access;
    private final SocialLimits limits;
    private final SocialProperties properties;
    private final MediaStorage storage;
    private final Clock clock;

    public ProfilePhotoService(ProfileRepository profiles, Relationships relationships, SocialAccess access,
                               SocialLimits limits, SocialProperties properties, MediaStorage storage, Clock clock) {
        this.profiles = profiles;
        this.relationships = relationships;
        this.access = access;
        this.limits = limits;
        this.properties = properties;
        this.storage = storage;
        this.clock = clock;
    }

    /** Replaces the caller's photo; answers the new key. A suspension stops it like any other profile change. */
    @Transactional
    public String upload(long userId, InputStream body) {
        access.requireCanConnect(userId);
        if (profiles.findByUserId(userId).isEmpty()) throw new ResourceNotFoundException(NO_PROFILE);
        limits.media(userId);
        byte[] input = readLimited(body, properties.maxMediaBytes());
        ImageSanitizer.Result clean;
        try {
            clean = ImageSanitizer.sanitizeSquare(input, properties.maxMediaBytes(), SIDE);
        } catch (ImageSanitizer.Refused e) {
            throw new InvalidRequestException(e.getMessage());
        }
        String key = MediaStorage.newKey();
        storage.write(key, clean.jpeg());
        Profile profile = profiles.lockByUserId(userId).orElseThrow(() -> new ResourceNotFoundException(NO_PROFILE));
        String old = profile.replacePhoto(key, now());
        profiles.saveAndFlush(profile);
        deleteAfterCommit(old);
        return key;
    }

    /** Removes the caller's photo (the same answer when there was none). */
    @Transactional
    public void remove(long userId) {
        access.requireMember(userId);
        profiles.lockByUserId(userId).ifPresent(profile -> {
            String old = profile.replacePhoto(null, now());
            profiles.saveAndFlush(profile);
            deleteAfterCommit(old);
        });
    }

    /**
     * Phase 17E (moderation): a moderator removes [userId]'s photo. Returns false when there was none. Called only by the
     * moderation package inside its own decision transaction.
     */
    public boolean removeByModerator(long userId, LocalDateTime now) {
        Profile profile = profiles.lockByUserId(userId).orElse(null);
        if (profile == null || profile.getPhotoKey() == null) return false;
        String old = profile.replacePhoto(null, now);
        profiles.saveAndFlush(profile);
        deleteAfterCommit(old);
        return true;
    }

    /** For a moderator's case view: the photo of [userId], or 404. */
    @Transactional(readOnly = true)
    public byte[] photoOf(long userId) {
        Profile profile = profiles.findByUserId(userId).orElse(null);
        if (profile == null || profile.getPhotoKey() == null) throw new ResourceNotFoundException(NOT_FOUND);
        return read(profile.getPhotoKey());
    }

    /** True when [userId] has a photo (a moderator's case view says so before a tap shows it). */
    @Transactional(readOnly = true)
    public boolean hasPhoto(long userId) {
        return profiles.findByUserId(userId).map(p -> p.getPhotoKey() != null).orElse(false);
    }

    /** The photo of the profile [profilePublicId] under [key], for a viewer who may see that profile. */
    @Transactional(readOnly = true)
    public byte[] photo(long viewerId, String profilePublicId, String key) {
        access.requireMember(viewerId);
        Profile profile = profilePublicId == null ? null : profiles.findByPublicId(profilePublicId).orElse(null);
        if (profile == null || !relationships.canSeeProfile(viewerId, profile.getUserId())
                || profile.getPhotoKey() == null || !profile.getPhotoKey().equals(key)) {
            throw new ResourceNotFoundException(NOT_FOUND);
        }
        return read(profile.getPhotoKey());
    }

    private byte[] read(String key) {
        try {
            return storage.read(key);
        } catch (UncheckedIOException | IllegalArgumentException e) {
            throw new ResourceNotFoundException(NOT_FOUND);
        }
    }

    private void deleteAfterCommit(String key) {
        if (key == null) return;
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            deleteQuietly(key);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                deleteQuietly(key);
            }
        });
    }

    private void deleteQuietly(String key) {
        try {
            storage.delete(key);
        } catch (RuntimeException e) {
            log.warn("A profile photo file could not be removed ({}); the clean-up job will retry",
                    e.getClass().getSimpleName());
        }
    }

    private static byte[] readLimited(InputStream body, int maxBytes) {
        try {
            byte[] bytes = body.readNBytes(maxBytes + 1);
            if (bytes.length > maxBytes) throw new InvalidRequestException("A photo can be at most 5 MB");
            return bytes;
        } catch (IOException e) {
            throw new InvalidRequestException("The photo could not be read");
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
}
