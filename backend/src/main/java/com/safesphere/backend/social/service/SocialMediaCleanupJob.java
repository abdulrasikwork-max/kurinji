package com.safesphere.backend.social.service;

import com.safesphere.backend.config.SocialProperties;
import com.safesphere.backend.social.entity.PostMedia;
import com.safesphere.backend.social.media.MediaStorage;
import com.safesphere.backend.social.repository.PostMediaRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Phase 13B (Q10): the one scheduled task of the social package. Every hour it deletes photos that were uploaded but
 * never attached to a post within 24 h (rows first, then files), and photo files that no row refers to any more (a
 * post deletion whose file removal failed, a crash between writing a file and its row). It only deletes; it never sends
 * anything. Turned off with app.social.media-cleanup-enabled=false (the tests call [run] directly).
 */
@Component
public class SocialMediaCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(SocialMediaCleanupJob.class);

    public record Outcome(int unattachedRows, int files) {
    }

    private final PostMediaRepository mediaRows;
    private final MediaStorage storage;
    private final SocialProperties properties;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final boolean enabled;

    public SocialMediaCleanupJob(PostMediaRepository mediaRows, MediaStorage storage, SocialProperties properties,
                                 Clock clock, PlatformTransactionManager transactions,
                                 @Value("${app.social.media-cleanup-enabled:true}") boolean enabled) {
        this.mediaRows = mediaRows;
        this.storage = storage;
        this.properties = properties;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactions);
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${app.social.media-cleanup-interval:PT1H}", initialDelayString = "PT5M")
    public void scheduled() {
        if (enabled) run();
    }

    /** One run. Never throws: a failure is logged (class name only) and the next run tries again. */
    public Outcome run() {
        Instant cutoff = clock.instant().minus(properties.unattachedMediaLifetime());
        int rows = 0;
        int files = 0;
        try {
            List<String> keys = tx.execute(status -> {
                List<PostMedia> stale = mediaRows.findUnattachedBefore(LocalDateTime.ofInstant(cutoff, ZoneOffset.UTC));
                mediaRows.deleteAll(stale);
                return stale.stream().map(PostMedia::getStorageKey).toList();
            });
            rows = keys.size();
            for (String key : keys) {
                storage.delete(key);
                files++;
            }
            // Files without a row, last written before the cut-off (younger files may still be getting their row).
            List<String> old = storage.keysOlderThan(cutoff);
            if (!old.isEmpty()) {
                Set<String> known = new HashSet<>();
                for (int i = 0; i < old.size(); i += 500) {
                    mediaRows.findByStorageKeyIn(old.subList(i, Math.min(old.size(), i + 500)))
                            .forEach(m -> known.add(m.getStorageKey()));
                }
                for (String key : old) {
                    if (!known.contains(key)) {
                        storage.delete(key);
                        files++;
                    }
                }
            }
        } catch (RuntimeException e) {
            log.warn("Photo clean-up failed ({}); trying again next run", e.getClass().getSimpleName());
        }
        if (rows > 0 || files > 0) log.info("Photo clean-up: {} unattached uploads, {} files removed", rows, files);
        return new Outcome(rows, files);
    }
}
