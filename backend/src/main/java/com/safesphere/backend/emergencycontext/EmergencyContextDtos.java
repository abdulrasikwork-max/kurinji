package com.safesphere.backend.emergencycontext;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** The two available projections of the Emergency Context Packet (Phase 4D). Records = explicit field allow-lists. */
public final class EmergencyContextDtos {

    private EmergencyContextDtos() {
    }

    /** Projection for the person themselves. */
    public record OwnerContext(
            String audience,
            Instant generatedAt,
            Emergency emergency,
            Journey journey,
            LatestLocation latestLocation,
            long locationCount,
            int highestLevel,
            List<TimelineEntry> escalations,
            List<AlertEntry> circleAlerts,
            EvidenceSummary evidence
    ) {
    }

    /**
     * Phase 5: how much evidence the vault holds for this emergency (counts only, OWNER projection only). The Safety
     * Circle projection deliberately has no evidence field at all.
     */
    public record EvidenceSummary(long items, long sealed, long uploading, long deleted) {
    }

    public record Emergency(Long id, String status, String triggerType, Instant startedAt, Instant endedAt) {
    }

    /** The Safe Walk that was active when the SOS started, if any. */
    public record Journey(Long id, String status, String destinationLabel, Instant startedAt, Instant endedAt) {
    }

    public record LatestLocation(BigDecimal latitude, BigDecimal longitude, BigDecimal accuracyMeters,
                                 Integer batteryPercent, Instant recordedAt, String mapLink) {
        @Override
        public String toString() {
            return "LatestLocation{location=<redacted>, recordedAt=" + recordedAt + "}";
        }
    }

    /** [source] is EMERGENCY or SAFE_WALK (from the walk that led to this emergency). */
    public record TimelineEntry(int level, String reason, Instant at, String source) {
    }

    /** [contactName] is null when the contact was deleted or unknown. No phone number is ever included. */
    public record AlertEntry(String kind, String channel, String status, String contactName, Instant occurredAt,
                             String source) {
    }

    /**
     * Projection for a Safety Circle contact. ONLY these fields (approved design: "name, time, type, location link").
     * No coordinates as numbers, no accuracy, no battery, no destination, no escalation history, no other contacts.
     */
    public record SafetyCircleContext(
            String audience,
            String personName,
            String type,
            String status,
            Instant startedAt,
            Instant endedAt,
            String locationLink,
            Instant locationRecordedAt
    ) {
        @Override
        public String toString() {
            return "SafetyCircleContext{type=" + type + ", status=" + status + ", location=<redacted>}";
        }
    }
}
