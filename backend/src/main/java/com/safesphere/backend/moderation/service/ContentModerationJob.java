package com.safesphere.backend.moderation.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Phase 13D (M18): hourly upkeep of content moderation: collapses content whose reports started to count (probation
 * ended), and deletes what retention says (closed cases with their reports, old strikes, ended pauses, rows whose
 * target is gone). It never decides anything a human must decide. Logs a count only.
 */
@Component
public class ContentModerationJob {

    private static final Logger log = LoggerFactory.getLogger(ContentModerationJob.class);

    private final ContentModerationService service;

    public ContentModerationJob(ContentModerationService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${app.moderation.content-interval:PT1H}", initialDelayString = "PT3M")
    public void run() {
        try {
            int changed = service.maintain();
            if (changed > 0) log.info("Content moderation upkeep: {} rows changed", changed);
        } catch (RuntimeException e) {
            log.warn("Content moderation upkeep failed ({}); trying again next run", e.getClass().getSimpleName());
        }
    }
}
