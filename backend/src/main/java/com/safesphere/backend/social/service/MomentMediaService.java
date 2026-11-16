package com.safesphere.backend.social.service;

import com.safesphere.backend.common.exception.InvalidRequestException;
import com.safesphere.backend.common.exception.ResourceNotFoundException;
import com.safesphere.backend.config.SocialProperties;
import com.safesphere.backend.social.entity.Moment;
import com.safesphere.backend.social.entity.MomentMedia;
import com.safesphere.backend.social.media.ImageSanitizer;
import com.safesphere.backend.social.media.MediaStorage;
import com.safesphere.backend.social.repository.MomentMediaRepository;
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
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase N4-B2: the photo of a moment (N-43, N-45: at most one, a photo only; no voice, no video). An upload is decoded
 * and written again as a new JPEG (ImageSanitizer: no EXIF, no position, no device) before anything is stored; the
 * uploaded bytes are never written to disk. Until a moment uses it, nobody can fetch an upload (the phone shows its own
 * copy); afterwards exactly the people who may see the moment (the one Visibility rule), by the MOMENT's id: the id of
 * a photo is never given to anyone but its uploader. Anything else is "not found".
 */
@Service
public class MomentMediaService {

    static final String NOT_FOUND = "Photo not found";
    static final String TOO_LARGE = "A photo can be at most 5 MB";

    private static final Logger log = LoggerFactory.getLogger(MomentMediaService.class);

    public record Uploaded(String mediaId, int width, int height) {
    }

    private final MomentMediaRepository mediaRows;
    private final MomentService moments;
    private final MediaStorage storage;
    private final SocialAccess access;
    private final MomentLimits limits;
    private final SocialProperties properties;
    private final Clock clock;

    public MomentMediaService(MomentMediaRepository mediaRows, MomentService moments, MediaStorage storage,
                              SocialAccess access, MomentLimits limits, SocialProperties properties, Clock clock) {
        this.mediaRows = mediaRows;
        this.moments = moments;
        this.storage = storage;
        this.access = access;
        this.limits = limits;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Reads at most the limit + 1 bytes (a longer body is refused without reading it all), sanitises, stores.
     * [declaredLength] is the request's Content-Length (-1 if unknown): a body that says it is too large is refused
     * before a byte is read. A refused upload does not count against the day's allowance.
     */
    public Uploaded upload(long userId, InputStream body, long declaredLength) {
        access.requireCanPost(userId);
        int max = properties.maxMediaBytes();
        if (declaredLength > max) throw new InvalidRequestException(TOO_LARGE);
        ImageSanitizer.Result clean;
        try {
            byte[] input = body.readNBytes(max + 1);
            if (input.length > max) throw new InvalidRequestException(TOO_LARGE);
            clean = ImageSanitizer.sanitize(input, max);
        } catch (IOException e) {
            throw new InvalidRequestException("The photo could not be read");
        } catch (ImageSanitizer.Refused e) {
            throw new InvalidRequestException(e.getMessage());
        }
        limits.photo(userId);
        String key = MediaStorage.newKey();
        storage.write(key, clean.jpeg());
        try {
            MomentMedia media = mediaRows.saveAndFlush(new MomentMedia(userId, key, clean.width(), clean.height(),
                    clean.jpeg().length, sha256(clean.jpeg()), now()));
            return new Uploaded(media.getPublicId(), media.getWidth(), media.getHeight());
        } catch (RuntimeException e) {
            storage.delete(key);
            throw e;
        }
    }

    /** The uploader removes a photo no moment uses (Remove on Create); the row first, the file after. */
    @Transactional
    public void deleteUnattached(long userId, String mediaId) {
        access.requireAccount(userId);
        List<MomentMedia> found = mediaId == null ? List.of() : mediaRows.findForAttach(mediaId);
        MomentMedia media = found.isEmpty() ? null : found.get(0);
        if (media == null || media.getOwnerId() != userId || media.getMomentId() != null) {
            throw new ResourceNotFoundException(NOT_FOUND);
        }
        mediaRows.delete(media);
        mediaRows.flush();
        try {
            storage.delete(media.getStorageKey());
        } catch (UncheckedIOException e) {
            // the row is gone, so the clean-up job removes the file as an orphan
            log.warn("Photo file removal failed ({}); the clean-up job will remove it", e.getClass().getSimpleName());
        }
    }

    /** The photo of a moment [viewerId] may see (always the server's re-encoded JPEG). */
    @Transactional(readOnly = true)
    public byte[] photoOf(long viewerId, String momentId) {
        access.requireMember(viewerId);
        Moment moment;
        try {
            moment = moments.requireVisible(viewerId, momentId, now());
        } catch (ResourceNotFoundException e) {
            throw new ResourceNotFoundException(NOT_FOUND);
        }
        List<MomentMedia> media = mediaRows.findByMomentIdIn(List.of(moment.getId()));
        if (media.isEmpty()) throw new ResourceNotFoundException(NOT_FOUND);
        try {
            return storage.read(media.get(0).getStorageKey());
        } catch (UncheckedIOException e) {
            throw new ResourceNotFoundException(NOT_FOUND);
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
