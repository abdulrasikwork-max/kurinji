package com.safesphere.backend.social.service;

import com.safesphere.backend.common.exception.InvalidRequestException;
import com.safesphere.backend.common.exception.ResourceNotFoundException;
import com.safesphere.backend.config.SocialProperties;
import com.safesphere.backend.social.entity.Post;
import com.safesphere.backend.social.entity.PostMedia;
import com.safesphere.backend.social.media.ImageSanitizer;
import com.safesphere.backend.social.media.MediaStorage;
import com.safesphere.backend.social.media.Mp4Sanitizer;
import com.safesphere.backend.social.repository.PostMediaRepository;
import com.safesphere.backend.social.repository.PostRepository;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 13B: photo upload and download. An upload is sanitised (ImageSanitizer: re-encoded JPEG, no metadata) before
 * anything is stored; the original bytes are never written to disk. Until a post uses it, only its owner can fetch it;
 * afterwards exactly the people who can see the post (the one Visibility rule). Anything else is "not found".
 *
 * Phase 17C (decision D-6): videos. The body is streamed into a temporary file of the media folder with a hard byte
 * limit (never held in memory), checked and stripped by Mp4Sanitizer into a second temporary file, and only that copy
 * is moved into place; the uploaded original is deleted in every case. The video's poster frame arrives separately and
 * is sanitised like any photo, under the same storage key. The owner may delete an upload that no post uses yet.
 */
@Service
public class MediaService {

    static final String NOT_FOUND = "Photo not found";
    static final String VIDEO_TOO_LARGE = "A video can be at most 50 MB";

    private static final Logger log = LoggerFactory.getLogger(MediaService.class);

    /** [durationMs] only for a video. */
    public record Uploaded(String mediaId, String kind, int width, int height, Integer durationMs) {
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
        ImageSanitizer.Result clean = sanitiseImage(body);
        String key = MediaStorage.newKey();
        storage.write(key, clean.jpeg());
        try {
            PostMedia media = mediaRows.saveAndFlush(new PostMedia(userId, key, clean.width(), clean.height(),
                    clean.jpeg().length, sha256(clean.jpeg()), now()));
            return uploaded(media);
        } catch (RuntimeException e) {
            storage.delete(key);
            throw e;
        }
    }

    /**
     * Phase 17C: a video (video/mp4). [declaredLength] is the request's Content-Length (-1 if unknown): a body that says
     * it is too large is refused before a byte is read; one that turns out larger is refused at the first byte over.
     */
    public Uploaded uploadVideo(long userId, InputStream body, long declaredLength) {
        access.requireCanPost(userId);
        limits.video(userId);
        int max = properties.maxVideoBytes();
        if (declaredLength > max) throw new InvalidRequestException(VIDEO_TOO_LARGE);
        Path raw = storage.newTemporary();
        Path stripped = null;
        String key = null;
        try {
            copyLimited(body, raw, max);
            stripped = storage.newTemporary();
            Mp4Sanitizer.Result checked;
            try {
                checked = Mp4Sanitizer.sanitize(raw, stripped);
            } catch (Mp4Sanitizer.Refused e) {
                throw new InvalidRequestException(e.getMessage());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            int bytes = (int) Files.size(stripped);
            String sha = sha256(stripped);
            key = MediaStorage.newKey();
            storage.moveVideo(stripped, key);
            stripped = null;
            PostMedia media = mediaRows.saveAndFlush(PostMedia.video(userId, key, checked.width(), checked.height(),
                    bytes, (int) checked.durationMs(), sha, now()));
            return uploaded(media);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (RuntimeException e) {
            if (key != null) storage.delete(key);
            throw e;
        } finally {
            deleteQuietly(raw);
            if (stripped != null) deleteQuietly(stripped);
        }
    }

    /**
     * Phase 17C: the poster frame of the caller's own video that no post uses yet (sent again after a lost answer: it
     * replaces the earlier one). Anything else is "not found".
     */
    @Transactional
    public Uploaded uploadPoster(long userId, String mediaId, InputStream body) {
        access.requireCanPost(userId);
        limits.media(userId);
        PostMedia media = ownUnattached(userId, mediaId);
        if (!media.isVideo()) throw new ResourceNotFoundException(NOT_FOUND);
        ImageSanitizer.Result clean = sanitiseImage(body);
        storage.write(media.getStorageKey(), clean.jpeg());
        media.setPoster(clean.jpeg().length);
        mediaRows.saveAndFlush(media);
        return uploaded(media);
    }

    /** Phase 17C: the owner removes an upload no post uses (Remove in the composer); rows first, files after. */
    @Transactional
    public void deleteUnattached(long userId, String mediaId) {
        access.requireMember(userId);
        PostMedia media = ownUnattached(userId, mediaId);
        mediaRows.delete(media);
        mediaRows.flush();
        String key = media.getStorageKey();
        try {
            storage.delete(key);
        } catch (UncheckedIOException e) {
            // the row is gone, so the clean-up job removes the file as an orphan
            log.warn("Media file removal failed ({}); the clean-up job will remove it", e.getClass().getSimpleName());
        }
    }

    /** The photo, or a video's poster frame (always a JPEG). */
    @Transactional(readOnly = true)
    public Download download(long viewerId, String mediaId) {
        PostMedia media = visibleOrNotFound(viewerId, mediaId);
        if (!media.isReady()) throw new ResourceNotFoundException(NOT_FOUND);
        try {
            return new Download(storage.read(media.getStorageKey()));
        } catch (UncheckedIOException e) {
            throw new ResourceNotFoundException(NOT_FOUND);
        }
    }

    /** Phase 17C: the stored (stripped) MP4 of a video the viewer may see. */
    @Transactional(readOnly = true)
    public Path video(long viewerId, String mediaId) {
        PostMedia media = visibleOrNotFound(viewerId, mediaId);
        if (!media.isVideo() || !storage.hasVideo(media.getStorageKey())) throw new ResourceNotFoundException(NOT_FOUND);
        return storage.videoFile(media.getStorageKey());
    }

    private PostMedia visibleOrNotFound(long viewerId, String mediaId) {
        access.requireMember(viewerId);
        PostMedia media = mediaId == null ? null : mediaRows.findByPublicId(mediaId).orElse(null);
        if (media == null || !visible(viewerId, media)) throw new ResourceNotFoundException(NOT_FOUND);
        return media;
    }

    /** Locked like PostService.attach, so a poster, a removal and an attach of the same upload never cross. */
    private PostMedia ownUnattached(long userId, String mediaId) {
        List<PostMedia> found = mediaId == null ? List.of() : mediaRows.findForAttach(List.of(mediaId));
        PostMedia media = found.isEmpty() ? null : found.get(0);
        if (media == null || media.getOwnerId() != userId || media.getPostId() != null) {
            throw new ResourceNotFoundException(NOT_FOUND);
        }
        return media;
    }

    private boolean visible(long viewerId, PostMedia media) {
        if (media.getPostId() == null) return media.getOwnerId() == viewerId;
        Post post = posts.findById(media.getPostId()).orElse(null);
        return post != null && relationships.canSeePost(viewerId, post, now());
    }

    private ImageSanitizer.Result sanitiseImage(InputStream body) {
        byte[] input = readLimited(body, properties.maxMediaBytes());
        try {
            return ImageSanitizer.sanitize(input, properties.maxMediaBytes());
        } catch (ImageSanitizer.Refused e) {
            throw new InvalidRequestException(e.getMessage());
        }
    }

    private static Uploaded uploaded(PostMedia media) {
        return new Uploaded(media.getPublicId(), media.getKind().name(), media.getWidth(), media.getHeight(),
                media.getDurationMs());
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

    /** Streams the body into [target], refusing it at the first byte over [maxBytes]. */
    private static void copyLimited(InputStream body, Path target, int maxBytes) {
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        try (OutputStream out = Files.newOutputStream(target, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            int n;
            while ((n = readSome(body, buffer)) > 0) {
                total += n;
                if (total > maxBytes) throw new InvalidRequestException(VIDEO_TOO_LARGE);
                out.write(buffer, 0, n);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e); // writing the temporary file failed: a server fault
        }
        if (total == 0) throw new InvalidRequestException("The video is empty");
    }

    private static int readSome(InputStream body, byte[] buffer) {
        try {
            return body.read(buffer);
        } catch (IOException e) {
            throw new InvalidRequestException("The video could not be read");
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Temporary upload removal failed ({}); the clean-up job will remove it",
                    e.getClass().getSimpleName());
        }
    }

    private static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(digest().digest(bytes));
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest = digest();
        try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
            in.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
}
