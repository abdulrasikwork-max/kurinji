package com.safesphere.backend.social.media;

import com.safesphere.backend.config.SocialProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Phase 13B: where post photos live (the EvidenceStorage pattern): one folder (app.social.media-dir), outside the
 * database and never served as static content. File names are random UUIDs chosen by the server, so nothing about the
 * person, the place or the content appears in a name and a client can never pick a path. Every file is a JPEG that
 * ImageSanitizer re-encoded (no metadata).
 * Phase 17C: a video is "<key>.mp4" (stripped by Mp4Sanitizer) with its poster "<key>.jpg" under the SAME key, so every
 * caller that deletes a key (post deletion, account erasure, the clean-up job) removes both. Uploads are streamed into
 * "<random>.tmp" files here first (never the system temp folder); the clean-up job removes any left behind.
 */
@Component
public class MediaStorage {

    private static final Pattern KEY = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final String SUFFIX = ".jpg";
    private static final String VIDEO_SUFFIX = ".mp4";
    private static final String TEMPORARY_SUFFIX = ".tmp";

    private final Path root;

    public MediaStorage(SocialProperties properties) {
        this.root = properties.mediaDir().toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create the social media folder " + root, e);
        }
    }

    public static String newKey() {
        return UUID.randomUUID().toString();
    }

    /** Writes the bytes to a temporary file, flushes it, then moves it into place (never a half-written photo). */
    public void write(String key, byte[] bytes) {
        Path target = file(key);
        Path temporary = root.resolve(checked(key) + ".tmp");
        try {
            Files.write(temporary, bytes);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // the clean-up job removes it later
            }
            throw new UncheckedIOException(e);
        }
    }

    /** Phase 17C: a new, empty temporary file for a streamed upload (the caller deletes it). */
    public Path newTemporary() {
        Path file = root.resolve(newKey() + TEMPORARY_SUFFIX);
        try {
            return Files.createFile(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Phase 17C: moves a finished, stripped video (a temporary file of this folder) into place as "<key>.mp4". */
    public void moveVideo(Path temporary, String key) {
        Path source = temporary.toAbsolutePath().normalize();
        if (!root.equals(source.getParent())) throw new IllegalArgumentException("Not a file of the media folder");
        try {
            Files.move(source, videoFile(key), StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Phase 17C: the stored video of [key] (served as a file resource, so ranges work without loading it). */
    public Path videoFile(String key) {
        return root.resolve(checked(key) + VIDEO_SUFFIX);
    }

    public boolean hasVideo(String key) {
        return Files.isRegularFile(videoFile(key));
    }

    public byte[] read(String key) {
        try {
            return Files.readAllBytes(file(key));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Every file of [key]: the photo or poster, and (Phase 17C) the video. */
    public void delete(String key) {
        try {
            Files.deleteIfExists(file(key));
            Files.deleteIfExists(videoFile(key));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public boolean exists(String key) {
        return Files.isRegularFile(file(key));
    }

    /**
     * Storage keys of the photo / poster / video files last written before [before], each key once; stray temporary
     * files that old are deleted on the way.
     */
    public List<String> keysOlderThan(Instant before) {
        Set<String> keys = new LinkedHashSet<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(root)) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                int dot = name.lastIndexOf('.');
                if (dot < 0) continue;
                String key = name.substring(0, dot);
                if (!KEY.matcher(key).matches()) continue;
                FileTime modified = Files.getLastModifiedTime(file);
                if (modified.toInstant().isBefore(before)) {
                    if (name.endsWith(TEMPORARY_SUFFIX)) Files.deleteIfExists(file);
                    else if (name.endsWith(SUFFIX) || name.endsWith(VIDEO_SUFFIX)) keys.add(key);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new ArrayList<>(keys);
    }

    public Path root() {
        return root;
    }

    private Path file(String key) {
        return root.resolve(checked(key) + SUFFIX);
    }

    /** Defence in depth: keys come from the database, but a key is still never trusted to be a safe file name. */
    private static String checked(String key) {
        if (key == null || !KEY.matcher(key).matches()) throw new IllegalArgumentException("Invalid storage key");
        return key;
    }
}
