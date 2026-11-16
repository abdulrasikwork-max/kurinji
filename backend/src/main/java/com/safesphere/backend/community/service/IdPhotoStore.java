package com.safesphere.backend.community.service;

import com.safesphere.backend.config.CommunityProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Phase N3: ID photos waiting for an admin, in their own folder (app.community.photo-dir), never next to the social
 * media and never served statically. Each file is "<random uuid>.idp" = AES-256-GCM ciphertext (CommunityKeys), so a
 * copied folder alone shows nothing. A file lives only until the decision, the withdrawal or the request's expiry; the
 * hourly sweep (CommunityUpkeepJob) deletes any file no request refers to.
 */
@Component
public class IdPhotoStore {

    private static final Logger log = LoggerFactory.getLogger(IdPhotoStore.class);
    private static final Pattern KEY = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final String SUFFIX = ".idp";

    private final Path root;
    private final CommunityKeys keys;

    public IdPhotoStore(CommunityProperties properties, CommunityKeys keys) {
        this.root = properties.photoDir().toAbsolutePath().normalize();
        this.keys = keys;
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create the ID photo folder " + root, e);
        }
    }

    public static String newKey() {
        return UUID.randomUUID().toString();
    }

    /** Encrypts [jpeg] and writes it under [key] (temporary file, then an atomic move: never half a file). */
    public void write(String key, byte[] jpeg) {
        Path target = file(key);
        Path temporary = root.resolve(checked(key) + ".tmp");
        try {
            Files.write(temporary, keys.encrypt(jpeg, key));
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // the sweep removes it later
            }
            throw new UncheckedIOException(e);
        }
    }

    /** The decrypted JPEG of [key]. */
    public byte[] read(String key) {
        try {
            return keys.decrypt(Files.readAllBytes(file(key)), key);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public boolean exists(String key) {
        return Files.isRegularFile(file(key));
    }

    /** Deletes the file now; a failure is logged (class only) and the sweep tries again. */
    public void delete(String key) {
        if (key == null) return;
        try {
            Files.deleteIfExists(file(key));
        } catch (IOException | RuntimeException e) {
            log.warn("ID photo file not deleted now ({}); the sweep retries", e.getClass().getSimpleName());
        }
    }

    /** Deletes the file once the current transaction commits (at once when there is none). */
    public void deleteAfterCommit(String key) {
        if (key == null) return;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    delete(key);
                }
            });
        } else {
            delete(key);
        }
    }

    /**
     * Deletes every file (and leftover temporary file) last written before [before] whose key is not in [keep].
     * Returns how many were deleted.
     */
    public int sweep(Set<String> keep, Instant before) {
        int deleted = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(root)) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                boolean ours = name.endsWith(SUFFIX) || name.endsWith(".tmp");
                if (!ours || !Files.isRegularFile(file)) continue;
                String key = name.substring(0, name.lastIndexOf('.'));
                if (name.endsWith(SUFFIX) && keep.contains(key)) continue;
                if (Files.getLastModifiedTime(file).toInstant().isAfter(before)) continue;
                Files.deleteIfExists(file);
                deleted++;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return deleted;
    }

    public Path root() {
        return root;
    }

    private Path file(String key) {
        return root.resolve(checked(key) + SUFFIX);
    }

    private static String checked(String key) {
        if (key == null || !KEY.matcher(key).matches()) throw new IllegalArgumentException("Not an ID photo key");
        return key;
    }
}
