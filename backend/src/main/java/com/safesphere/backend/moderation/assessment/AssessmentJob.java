package com.safesphere.backend.moderation.assessment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Phase 13E (E7, E25): retries checks the service could not answer, publishes a new account's content that waited
 * long enough, and deletes old assessments. It never decides anything a human must decide. Logs a count only.
 */
@Component
public class AssessmentJob {

    private static final Logger log = LoggerFactory.getLogger(AssessmentJob.class);

    private final AssessmentService service;

    public AssessmentJob(AssessmentService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${app.moderation.ai.job-interval:PT5M}", initialDelayString = "PT1M")
    public void run() {
        try {
            int changed = service.maintain();
            if (changed > 0) log.info("Automatic content check upkeep: {} rows changed", changed);
        } catch (RuntimeException e) {
            log.warn("Automatic content check upkeep failed ({}); trying again next run", e.getClass().getSimpleName());
        }
    }
}
