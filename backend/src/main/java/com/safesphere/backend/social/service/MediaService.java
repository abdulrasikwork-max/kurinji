package com.safesphere.backend.social.service;

import com.safesphere.backend.common.exception.InvalidRequestException;
import com.safesphere.backend.common.exception.ResourceNotFoundException;
import com.safesphere.backend.config.SocialProperties;
import com.safesphere.backend.social.entity.Post;
import com.safesphere.backend.social.entity.PostMedia;
import com.safesphere.backend.social.media.ImageSanitizer;
import com.safesphere.backend.social.media.MediaStorage;
import com.safesphere.backend.social.repository.PostMediaRepository;
import com.safesphere.backend.social.repository.PostRepository;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 13B: photo upload and download. An upload is sanitised (ImageSanitizer: re-encoded JPEG, no metadata) before
 * anything is stored; the original bytes are never written to disk. Until a post uses it, only its owner can fetch it;
 * afterwards exactly the people who can see the post (the one Visibility rule). Anything else is "not found".
 */
@Service
public class MediaService {

    static final String NOT_FOUND = "Photo not found";

    public record Uploaded(String mediaId, int width, int height) {
    }

    public record Download(byte[] bytes) {
    }

    private final PostMediaRepository mediaRows;
    private final PostRepository posts;
    private final MediaStorage storage;
    private final Relationships relationships;
    private final SocialAccess access;
    private final SocialLimits limits;
    private final SocialProperties properties;
    private final Clock clock;

    public MediaService(PostMediaRepository mediaRows, PostRepository posts, MediaStorage storage,
                        Relationships relationships, SocialAccess access, SocialLimits limits,
                        SocialProperties properties, Clock clock) {
        this.mediaRows = mediaRows;
        this.posts = posts;
        this.storage = storage;
        this.relationships = relationships;
        this.access = access;
        this.limits = limits;
        this.properties = properties;
        this.clock = clock;
    }

    /** Reads at most the limit + 1 bytes (a longer body is refused without reading it all), sanitises, stores. */
    public Uploaded upload(long userId, InputStream body) {
        access.requireCanPost(userId); // Phase 13D: photos are posting (M9)
        limits.media(userId);
        byte[] input = readLimited(body, properties.maxMediaBytes());
        ImageSanitizer.Result clean;
        try {
            clean = ImageSanitizer.sanitize(input, properties.maxMediaBytes());
        } catch (ImageSanitizer.Refused e) {
            throw new InvalidRequestException(e.getMessage());
        }
        String key = MediaStorage.newKey();
        storage.write(key, clean.jpeg());
        try {
            PostMedia media = mediaRows.saveAndFlush(new PostMedia(userId, key, clean.width(), clean.height(),
                    clean.jpeg().length, sha256(clean.jpeg()), now()));
            return new Uploaded(media.getPublicId(), media.getWidth(), media.getHeight());
        } catch (RuntimeException e) {
            storage.delete(key);
            throw e;
        }
    }

    @Transactional(readOnly = true)
    public Download download(long viewerId, String mediaId) {
        access.requireMember(viewerId);
        PostMedia media = mediaId == null ? null : mediaRows.findByPublicId(mediaId).orElse(null);
        if (media == null || !visible(viewerId, media)) throw new ResourceNotFoundException(NOT_FOUND);
        try {
            return new Download(storage.read(media.getStorageKey()));
        } catch (UncheckedIOException e) {
            throw new ResourceNotFoundException(NOT_FOUND);
        }
    }

    private boolean visible(long viewerId, PostMedia media) {
        if (media.getPostId() == null) return media.getOwnerId() == viewerId;
        Post post = posts.findById(media.getPostId()).orElse(null);
        return post != null && relationships.canSeePost(viewerId, post, now());
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

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
}
