package com.safesphere.backend.evidence.service;

import com.safesphere.backend.common.exception.InvalidRequestException;
import com.safesphere.backend.config.EvidenceProperties;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Where the encrypted evidence files live: one folder on disk (app.evidence.storage-dir), outside the database and
 * never served as static content. File names are random UUIDs chosen by the server ([storage key]), so nothing about
 * the person or the content appears in a file name, and a client can never pick a path.
 *
 *   <key>.part   bytes still arriving (resumable)
 *   <key>.ssev   sealed: every byte arrived and the hash was verified
 *
 * The server never sees plaintext: these files are exactly the ciphertext the phone produced.
 */
@Component
public class EvidenceStorage {

    private static final Pattern KEY = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final int BUFFER = 64 * 1024;

    private final Path root;

    public EvidenceStorage(EvidenceProperties properties) {
        this.root = properties.storageDir().toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create the evidence storage folder " + root, e);
        }
    }

    public static String newKey() {
        return UUID.randomUUID().toString();
    }

    public Path root() {
        return root;
    }

    /** Creates the empty upload file for a new item. */
    public void create(String key) {
        try {
            Files.deleteIfExists(partial(key));
            Files.createFile(partial(key));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Writes one upload piece at [offset]. The file is first cut back to [offset], so bytes written by an earlier
     * request whose database update did not commit can never stay behind. Reads at most [maxBytes]; a longer body is
     * refused and leaves the file as it was. Returns the number of bytes written. Flushed to the disk before returning.
     */
    public long write(String key, long offset, InputStream body, int maxBytes) {
        try (FileChannel channel = FileChannel.open(partial(key), StandardOpenOption.WRITE, StandardOpenOption.CREATE)) {
            if (channel.size() < offset) {
                throw new IllegalStateException("Evidence file is shorter than the recorded upload progress");
            }
            channel.truncate(offset);
            channel.position(offset);
            byte[] buffer = new byte[BUFFER];
            long written = 0;
            int read;
            while ((read = body.read(buffer)) != -1) {
                if (written + read > maxBytes) {
                    channel.truncate(offset);
                    throw new InvalidRequestException("Upload piece is larger than " + maxBytes + " bytes");
                }
                ByteBuffer slice = ByteBuffer.wrap(buffer, 0, read);
                while (slice.hasRemaining()) channel.write(slice);
                written += read;
            }
            channel.force(true);
            return written;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Size and SHA-256 of the received bytes. Reads the sealed file instead when an earlier seal moved the file but its
     * database update did not commit (the bytes are the same either way).
     */
    public Digest digestForSeal(String key) {
        Path file = Files.exists(partial(key)) ? partial(key) : sealed(key);
        MessageDigest digest = Sha256.newDigest();
        long size = 0;
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[BUFFER];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
                size += read;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new Digest(size, Sha256.hex(digest.digest()));
    }

    /** Size and SHA-256 of the SEALED file as it is on disk now (empty when the file is missing). */
    public java.util.Optional<Digest> digestOfSealed(String key) {
        Path file = sealed(key);
        if (!Files.isRegularFile(file)) return java.util.Optional.empty();
        MessageDigest digest = Sha256.newDigest();
        long size = 0;
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[BUFFER];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
                size += read;
            }
        } catch (IOException e) {
            return java.util.Optional.empty(); // unreadable = not intact
        }
        return java.util.Optional.of(new Digest(size, Sha256.hex(digest.digest())));
    }

    public void seal(String key) {
        try {
            if (!Files.exists(partial(key)) && Files.exists(sealed(key))) return; // already moved by an earlier attempt
            Files.move(partial(key), sealed(key), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void resetPartial(String key) {
        try {
            Files.deleteIfExists(sealed(key));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try (FileChannel channel = FileChannel.open(partial(key), StandardOpenOption.WRITE, StandardOpenOption.CREATE)) {
            channel.truncate(0);
            channel.force(true);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void delete(String key) {
        try {
            Files.deleteIfExists(partial(key));
            Files.deleteIfExists(sealed(key));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Path sealedFile(String key) {
        return sealed(key);
    }

    public boolean sealedExists(String key) {
        return Files.isRegularFile(sealed(key));
    }

    private Path partial(String key) {
        return root.resolve(checked(key) + ".part");
    }

    private Path sealed(String key) {
        return root.resolve(checked(key) + ".ssev");
    }

    /** Defence in depth: keys come from the database, but a key is still never trusted to be a safe file name. */
    private static String checked(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Invalid storage key");
        }
        return key;
    }

    public record Digest(long size, String sha256) {
    }
}
