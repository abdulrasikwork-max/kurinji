package com.safesphere.backend.social.service;

import com.safesphere.backend.config.SocialProperties;
import com.safesphere.backend.social.entity.Moment;
import com.safesphere.backend.social.entity.MomentMedia;
import com.safesphere.backend.social.media.MediaStorage;
import com.safesphere.backend.social.policy.MomentPolicy;
import com.safesphere.backend.social.repository.MomentMediaRepository;
import com.safesphere.backend.social.repository.MomentRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The one scheduled task of the social package (13B Q10; since N4 for moments). Every hour it deletes photos that
 * were uploaded but never attached to a moment within 24 h (rows first, then files), and photo files that no row refers
 * to any more (a deletion whose file removal failed, a crash between writing a file and its row, the files of the
 * posts V35 dropped). Profile photos are kept (their keys are on the profile). It only deletes; it never sends
 * anything. Turned off with app.social.media-cleanup-enabled=false (the tests call [run] directly).
 *
 * Phase N4 (N-47): it also purges moments. A moment whose 24 hours ended more than 7 days ago goes, with its photo row
 * and file, unless its author keeps it, an open moderation case needs it, or a moderator removed it (retention of
 * removed content: N8). A moment that was never visible (waiting for a human) has no end and is never purged (N-57).
 */
@Component
public class SocialMediaCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(SocialMediaCleanupJob.class);

    /** Moments purged per transaction, and the most per run (the next run carries on). */
    private static final int PURGE_BATCH = 200;
    private static final int PURGE_BATCHES_PER_RUN = 50;

    /** [moments] = purged moments (N4). */
    public record Outcome(int unattachedRows, int files, int moments) {
    }

    private final MomentRepository moments;
    private final MomentMediaRepository momentMedia;
    private final com.safesphere.backend.social.repository.ProfileRepository profiles;
    private final MediaStorage storage;
    private final SocialProperties properties;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final boolean enabled;
    private final ResponseData responseData;

    public SocialMediaCleanupJob(MomentRepository moments,
                                 MomentMediaRepository momentMedia,
                                 com.safesphere.backend.social.repository.ProfileRepository profiles, MediaStorage storage, SocialProperties properties,
                                 Clock clock, PlatformTransactionManager transactions,
                                 @Value("${app.social.media-cleanup-enabled:true}") boolean enabled,
                                 ResponseData responseData) {
        this.responseData = responseData;
        this.moments = moments;
        this.momentMedia = momentMedia;
        this.profiles = profiles;
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
        LocalDateTime cutoffTime = LocalDateTime.ofInstant(cutoff, ZoneOffset.UTC);
        int rows = 0;
        int files = 0;
        int purged = 0;
        try {
            // N4: ended moments whose 7 days are over (rows first, then their photo files).
            LocalDateTime endedBefore = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
                    .minus(MomentPolicy.PURGE_AFTER);
            // N5 (N-98): what people said under moments whose 7 days are over, also under kept ones.
            for (int batch = 0; batch < PURGE_BATCHES_PER_RUN; batch++) {
                int cleared = tx.execute(status -> {
                    List<Long> due = moments.withResponsesEndedBefore(endedBefore, PageRequest.of(0, PURGE_BATCH));
                    responseData.deleteFor(due);
                    return due.size();
                });
                if (cleared < PURGE_BATCH) break;
            }
            for (int batch = 0; batch < PURGE_BATCHES_PER_RUN; batch++) {
                List<String> keys = new ArrayList<>();
                int gone = tx.execute(status -> {
                    List<Moment> due = moments.purgeable(endedBefore, PageRequest.of(0, PURGE_BATCH));
                    if (due.isEmpty()) return 0;
                    List<MomentMedia> media = momentMedia.findByMomentIdIn(due.stream().map(Moment::getId).toList());
                    media.forEach(m -> keys.add(m.getStorageKey()));
                    momentMedia.deleteAll(media);
                    responseData.deleteFor(due.stream().map(Moment::getId).toList());
                    moments.deleteAll(due);
                    return due.size();
                });
                purged += gone;
                for (String key : keys) {
                    storage.delete(key);
                    files++;
                }
                if (gone < PURGE_BATCH) break;
            }

            List<String> keys = tx.execute(status -> {
                // Moment photos that were uploaded and never shared.
                List<String> unattached = new ArrayList<>();
                List<MomentMedia> staleMoment = momentMedia.findUnattachedBefore(cutoffTime);
                momentMedia.deleteAll(staleMoment);
                staleMoment.forEach(m -> unattached.add(m.getStorageKey()));
                return unattached;
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
                    List<String> part = old.subList(i, Math.min(old.size(), i + 500));
                    momentMedia.findByStorageKeyIn(part).forEach(m -> known.add(m.getStorageKey()));
                    // A profile photo has no media row; its key is on the profile.
                    known.addAll(profiles.photoKeysIn(part));
                }
                for (String key : old) {
                    if (!known.contains(key)) {
                        storage.delete(key);
                        files++;
                    }
                }
            }
        } catch (RuntimeException e) {
            log.warn("Social clean-up failed ({}); trying again next run", e.getClass().getSimpleName());
        }
        if (rows > 0 || files > 0 || purged > 0) {
            log.info("Social clean-up: {} moments purged, {} unattached uploads, {} files removed", purged, rows, files);
        }
        return new Outcome(rows, files, purged);
    }
}
