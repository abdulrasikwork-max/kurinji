package com.safesphere.backend.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The app.explore.understanding.* settings (Phase 17G-3, docs/PHASE17G_PLAN.md section 5): the OPTIONAL "smart understanding"
 * of a typed Explore request. It is OFF unless an operator switches it on AND the internal service holds a provider key; the
 * backend itself never holds that key (it only holds the internal service key, like the moderation check).
 *
 * @param mode           OFF (default) or ON
 * @param url            the internal service (default 127.0.0.1:8091, the same process as the moderation check)
 * @param key            the shared internal key sent as X-Moderation-Key (env MODERATION_SERVICE_KEY; never logged)
 * @param connectTimeout connecting to the service
 * @param timeout        the whole call (the service itself waits at most 6 s for the provider)
 * @param userDailyCap   most provider calls one person causes per UTC day
 * @param globalDailyCap most provider calls all people together cause per UTC day (a spending ceiling of our own)
 * @param breakerPause   after any provider failure no call is made for this long (a spend-limit or credit refusal repeats
 *                       until it is fixed, and carries no retry-after)
 * @param statusCache    how long "the service has a key" is remembered
 */
@ConfigurationProperties(prefix = "app.explore.understanding")
public record ExploreUnderstandingProperties(Mode mode, String url, String key, Duration connectTimeout,
                                             Duration timeout, Integer userDailyCap, Integer globalDailyCap,
                                             Duration breakerPause, Duration statusCache) {

    public enum Mode { OFF, ON }

    public ExploreUnderstandingProperties {
        if (mode == null) mode = Mode.OFF;
        if (url == null || url.isBlank()) url = "http://127.0.0.1:8091";
        if (key == null) key = "";
        if (connectTimeout == null) connectTimeout = Duration.ofMillis(300);
        if (timeout == null) timeout = Duration.ofSeconds(9);
        if (userDailyCap == null) userDailyCap = 20;
        if (globalDailyCap == null) globalDailyCap = 300;
        if (breakerPause == null) breakerPause = Duration.ofMinutes(10);
        if (statusCache == null) statusCache = Duration.ofMinutes(2);
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new IllegalStateException("app.explore.understanding.url must be an http(s) URL");
        }
        if (connectTimeout.isNegative() || connectTimeout.isZero() || timeout.isNegative() || timeout.isZero()
                || timeout.compareTo(Duration.ofSeconds(15)) > 0 || userDailyCap < 0 || globalDailyCap < 0
                || breakerPause.isNegative() || statusCache.isNegative()) {
            throw new IllegalStateException("app.explore.understanding.* values are invalid");
        }
    }

    /** Smart understanding runs only when it is switched on AND the internal key (16+ characters) is present. */
    public boolean active() {
        return mode == Mode.ON && key.length() >= 16;
    }

    /** Never the key. */
    @Override
    public String toString() {
        return "ExploreUnderstandingProperties[mode=" + mode + ", url=" + url + ", userDailyCap=" + userDailyCap
                + ", globalDailyCap=" + globalDailyCap + "]";
    }
}
