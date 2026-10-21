package com.safesphere.backend.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The app.community.* settings (Phase 6, docs/PHASE6.md). Every value has a safe default.
 *
 * @param publishDelayMin / publishDelayMax a contribution starts counting a RANDOM time in this range after it was
 *                        made, so nobody nearby can link a new signal to the person who just used their phone
 * @param newAccountProbation a brand-new account's contributions count only once the account is this old
 *                        (makes "create accounts to confirm my own report" slower)
 * @param maxReportsPerDay        reports one person may make in 24 h
 * @param maxContributionsPerDay  reports + confirmations + "no longer true" answers in 24 h
 * @param maxReportsPerAreaPerDay reports in one ~1 km area cell in 24 h (stops "report bombing" one place)
 * @param areaQueriesPerHour      area look-ups per person per hour (anti-scraping)
 * @param distinctAreasPerHour    DIFFERENT areas one person may look at per hour (anti-reconnaissance)
 * @param maxSignalsPerArea       most signals one area answer contains (nearest first)
 * @param writesPer10Minutes      Phase 7: reports + answers + flags one person may send in any 10 minutes (bots and
 *                                scripted bursts; a person on foot rarely needs more than a few)
 */
@ConfigurationProperties(prefix = "app.community")
public record CommunityProperties(Duration publishDelayMin, Duration publishDelayMax, Duration newAccountProbation,
                                  Integer maxReportsPerDay, Integer maxContributionsPerDay,
                                  Integer maxReportsPerAreaPerDay, Integer areaQueriesPerHour,
                                  Integer distinctAreasPerHour, Integer maxSignalsPerArea,
                                  Integer writesPer10Minutes) {

    public CommunityProperties {
        if (publishDelayMin == null) publishDelayMin = Duration.ofMinutes(10);
        if (publishDelayMax == null) publishDelayMax = Duration.ofMinutes(30);
        if (newAccountProbation == null) newAccountProbation = Duration.ofHours(24);
        if (maxReportsPerDay == null) maxReportsPerDay = 10;
        if (maxContributionsPerDay == null) maxContributionsPerDay = 30;
        if (maxReportsPerAreaPerDay == null) maxReportsPerAreaPerDay = 3;
        if (areaQueriesPerHour == null) areaQueriesPerHour = 120;
        if (distinctAreasPerHour == null) distinctAreasPerHour = 15;
        if (maxSignalsPerArea == null) maxSignalsPerArea = 100;
        if (writesPer10Minutes == null) writesPer10Minutes = 10;
        if (publishDelayMin.isNegative() || publishDelayMax.compareTo(publishDelayMin) < 0
                || newAccountProbation.isNegative() || maxReportsPerDay < 1 || maxContributionsPerDay < 1
                || maxReportsPerAreaPerDay < 1 || areaQueriesPerHour < 1 || distinctAreasPerHour < 1
                || maxSignalsPerArea < 1 || writesPer10Minutes < 1) {
            throw new IllegalStateException("app.community.* values are invalid");
        }
    }
}
