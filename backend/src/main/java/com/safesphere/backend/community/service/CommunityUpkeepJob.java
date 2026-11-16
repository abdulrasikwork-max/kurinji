package com.safesphere.backend.community.service;

import com.safesphere.backend.community.entity.CommunityAction;
import com.safesphere.backend.community.entity.CommunityEnums.ActionKind;
import com.safesphere.backend.community.entity.CommunityEnums.EndReason;
import com.safesphere.backend.community.entity.Membership;
import com.safesphere.backend.community.entity.VerificationRequest;
import com.safesphere.backend.community.repository.CommunityActionRepository;
import com.safesphere.backend.community.repository.MembershipRepository;
import com.safesphere.backend.community.repository.NumberRelinkRepository;
import com.safesphere.backend.community.repository.VerificationRequestRepository;
import com.safesphere.backend.config.CommunityProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Phase N3: the community's only scheduled work. Hourly; it only deletes and ends, never sends anything:
 *   1. undecided requests past their life (7 days) go, and their ID photo files right after;
 *   2. "moving to a new number" codes past their life go;
 *   3. memberships past the end of 31 July of their graduation year are marked GRADUATED (access already stopped at
 *      that minute: MembershipLookup reads ends_at) and lose any verifier flag; logged with no actor (the system);
 *   4. any ID photo file no request refers to (older than an hour, so never one being written) is deleted.
 * Turned off with app.community.job-enabled=false (the tests call [run]). Logs counts only.
 */
@Component
public class CommunityUpkeepJob {

    private static final Logger log = LoggerFactory.getLogger(CommunityUpkeepJob.class);
    private static final int BATCH = 200;

    public record Outcome(int requests, int relinks, int graduated, int files) {
    }

    private final VerificationRequestRepository requests;
    private final NumberRelinkRepository relinks;
    private final MembershipRepository memberships;
    private final CommunityActionRepository actions;
    private final IdPhotoStore photos;
    private final CommunityProperties properties;
    private final Clock clock;
    private final TransactionTemplate tx;

    public CommunityUpkeepJob(VerificationRequestRepository requests, NumberRelinkRepository relinks,
                              MembershipRepository memberships, CommunityActionRepository actions,
                              IdPhotoStore photos, CommunityProperties properties, Clock clock,
                              PlatformTransactionManager transactions) {
        this.requests = requests;
        this.relinks = relinks;
        this.memberships = memberships;
        this.actions = actions;
        this.photos = photos;
        this.properties = properties;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactions);
    }

    @Scheduled(fixedDelayString = "${app.community.job-interval:PT1H}", initialDelayString = "PT3M")
    public void scheduled() {
        if (properties.jobEnabled()) run();
    }

    /** One run. Never throws: a failure is logged (class name only) and the next run tries again. */
    public Outcome run() {
        int expiredRequests = 0;
        int expiredRelinks = 0;
        int graduated = 0;
        int files = 0;
        try {
            LocalDateTime now = now();
            expiredRequests = tx.execute(status -> {
                List<VerificationRequest> gone = requests.expired(now, PageRequest.of(0, BATCH));
                for (VerificationRequest r : gone) {
                    requests.delete(r);
                    photos.deleteAfterCommit(r.getPhotoKey());
                }
                return gone.size();
            });
            expiredRelinks = tx.execute(status -> relinks.deleteExpired(now));
            graduated = tx.execute(status -> {
                List<Membership> ended = memberships.pastTheirEnd(now, PageRequest.of(0, BATCH));
                for (Membership m : ended) {
                    m.end(EndReason.GRADUATED, m.getEndsAt());
                    memberships.save(m);
                    actions.save(new CommunityAction(m.getCommunityId(), null, m.getUserId(), ActionKind.ENDED, null,
                            EndReason.GRADUATED.name(), null, now));
                }
                return ended.size();
            });
            files = photos.sweep(new HashSet<>(requests.photoKeys()), clock.instant().minus(Duration.ofHours(1)));
        } catch (RuntimeException e) {
            log.warn("Community upkeep failed this time ({}); the next run tries again", e.getClass().getSimpleName());
        }
        if (expiredRequests + expiredRelinks + graduated + files > 0) {
            log.info("Community upkeep: {} expired requests, {} expired moves, {} graduated, {} stray photo files",
                    expiredRequests, expiredRelinks, graduated, files);
        }
        return new Outcome(expiredRequests, expiredRelinks, graduated, files);
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
