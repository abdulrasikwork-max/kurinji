package com.safesphere.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The app.evaluation.* settings (Phase 9B outcome counters, docs/PHASE9.md §27). Every value has a safe default; invalid
 * values stop the server at start-up, like the other app.* settings.
 *
 * @param enabled        counters on or off. Off = nothing is counted, no weekly snapshot, and the stored rows are
 *                       deleted at the next clean-up (as for pattern memory)
 * @param retentionWeeks rows older than this many weeks are deleted (at most 104, the design's limit)
 */
@ConfigurationProperties(prefix = "app.evaluation")
public record EvaluationProperties(Boolean enabled, Integer retentionWeeks) {

    public EvaluationProperties {
        if (enabled == null) enabled = true;
        if (retentionWeeks == null) retentionWeeks = 104;
        if (retentionWeeks < 1 || retentionWeeks > 104) {
            throw new IllegalStateException("app.evaluation.* values are invalid");
        }
    }
}
