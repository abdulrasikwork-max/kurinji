package com.safesphere.backend.evidence.service;

import com.safesphere.backend.evidence.entity.EvidenceItem;
import com.safesphere.backend.evidence.entity.EvidenceStatus;
import com.safesphere.backend.evidence.repository.EvidenceItemRepository;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Start-up check that the storage folder and the database agree.
 *
 *  - Files no row refers to (a declaration whose transaction failed, a deleted account, a delete whose file removal
 *    was interrupted) are removed once they are older than an hour, so no ciphertext lingers without an owner.
 *  - A SEALED row whose file is missing is reported loudly: the record claims evidence the server cannot produce.
 *
 * Logs counts only, never names, hashes or users.
 */
@Component
public class EvidenceStorageReconciler implements ApplicationRunner {

    static final Duration ORPHAN_AGE = Duration.ofHours(1);
    private static final Logger log = LoggerFactory.getLogger(EvidenceStorageReconciler.class);

    private final EvidenceItemRepository repository;
    private final EvidenceStorage storage;
    private final Clock clock;

    public EvidenceStorageReconciler(EvidenceItemRepository repository, EvidenceStorage storage, Clock clock) {
        this.repository = repository;
        this.storage = storage;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        Result result = reconcile();
        if (result.removedOrphans() > 0) {
            log.info("Evidence storage: removed {} file(s) no record refers to", result.removedOrphans());
        }
        if (result.missingSealedFiles() > 0) {
            log.error("Evidence storage: {} sealed item(s) have no file on disk; check the storage folder and backups",
                    result.missingSealedFiles());
        }
    }

    public Result reconcile() {
        Set<String> known = new HashSet<>(repository.findAllStorageKeys());
        Instant cutoff = Instant.now(clock).minus(ORPHAN_AGE);
        int removed = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(storage.root())) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                int dot = name.lastIndexOf('.');
                String key = dot < 0 ? name : name.substring(0, dot);
                if (known.contains(key) || !Files.isRegularFile(file)) continue;
                FileTime modified = Files.getLastModifiedTime(file);
                if (modified.toInstant().isBefore(cutoff)) {
                    Files.deleteIfExists(file);
                    removed++;
                }
            }
        } catch (IOException e) {
            log.warn("Evidence storage could not be checked", e);
        }
        int missing = 0;
        for (EvidenceItem item : repository.findByStatus(EvidenceStatus.SEALED)) {
            if (!storage.sealedExists(item.getStorageKey())) missing++;
        }
        return new Result(removed, missing);
    }

    public record Result(int removedOrphans, int missingSealedFiles) {
    }
}
