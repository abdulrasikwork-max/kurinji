package com.safesphere.backend.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The app.moderation.ai.* settings (Phase 13E, docs/PHASE13E_PLAN.md E3-E7, E25). Every value has a safe default.
 *
 * @param enabled         the automatic check runs (only with a [key]: without one it is off and says so at start-up)
 * @param url             the moderation service, internal network only (default 127.0.0.1:8091)
 * @param key             the shared key sent as X-Moderation-Key (env MODERATION_SERVICE_KEY; never logged)
 * @param connectTimeout  connecting to the service
 * @param timeout         the whole call; after it the content is published (or, for a new account, waits)
 * @param modelMode       OFF / SHADOW / ACTIVE: whether a model's scores may act (no model ships in 13E: OFF)
 * @param probationWaitMax a new account's content waits for an unavailable check at most this long, then publishes
 * @param retryFor        a check that keeps failing is retried this long, then given up (the content stays published)
 * @param retention       assessments are deleted after this, unless their content has an open case or appeal
 * @param jobInterval     the retry and clean-up job
 */
@ConfigurationProperties(prefix = "app.moderation.ai")
public record ModerationAiProperties(Boolean enabled, String url, String key, Duration connectTimeout,
                                     Duration timeout, ModelMode modelMode, Duration probationWaitMax,
                                     Duration retryFor, Duration retention, Duration jobInterval) {

    public enum ModelMode { OFF, SHADOW, ACTIVE }

    public ModerationAiProperties {
        if (enabled == null) enabled = true;
        if (url == null || url.isBlank()) url = "http://127.0.0.1:8091";
        if (key == null) key = "";
        if (connectTimeout == null) connectTimeout = Duration.ofMillis(300);
        if (timeout == null) timeout = Duration.ofMillis(1500);
        if (modelMode == null) modelMode = ModelMode.OFF;
        if (probationWaitMax == null) probationWaitMax = Duration.ofHours(2);
        if (retryFor == null) retryFor = Duration.ofHours(24);
        if (retention == null) retention = Duration.ofDays(90);
        if (jobInterval == null) jobInterval = Duration.ofMinutes(5);
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new IllegalStateException("app.moderation.ai.url must be an http(s) URL");
        }
        if (connectTimeout.isNegative() || connectTimeout.isZero() || timeout.isNegative() || timeout.isZero()
                || timeout.compareTo(Duration.ofSeconds(10)) > 0 || probationWaitMax.isNegative()
                || retryFor.isNegative() || retention.isNegative() || jobInterval.isNegative() || jobInterval.isZero()) {
            throw new IllegalStateException("app.moderation.ai.* values are invalid");
        }
    }

    /** The check runs only when it is switched on AND has a key of at least 16 characters. */
    public boolean active() {
        return enabled && key.length() >= 16;
    }

    /** Never the key. */
    @Override
    public String toString() {
        return "ModerationAiProperties[enabled=" + enabled + ", url=" + url + ", modelMode=" + modelMode + "]";
    }
}
