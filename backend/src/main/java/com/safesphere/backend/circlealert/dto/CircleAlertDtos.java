package com.safesphere.backend.circlealert.dto;

import com.safesphere.backend.circlealert.entity.CircleAlertEnums.Channel;
import com.safesphere.backend.circlealert.entity.CircleAlertEnums.Kind;
import com.safesphere.backend.circlealert.entity.CircleAlertEnums.Status;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/** Request / response bodies of the circle alert log (Phase 4D). */
public final class CircleAlertDtos {

    private CircleAlertDtos() {
    }

    /**
     * One reported attempt. [clientRef] is a UUID made by the phone for this attempt and contact: re-sending the same
     * report is harmless, and a later report with the same reference may only move the status forward
     * (SENT -> DELIVERED). No phone number or message text is sent: the contact is referenced by its id.
     */
    public record Report(
            @NotNull(message = "Reference is required")
            @Pattern(regexp = "^[0-9a-fA-F-]{36}$", message = "Reference must be a UUID")
            String clientRef,

            Long contactId,

            @NotNull(message = "Kind is required") Kind kind,

            @NotNull(message = "Channel is required") Channel channel,

            @NotNull(message = "Status is required") Status status,

            @NotNull(message = "Time is required") Instant occurredAt
    ) {
    }

    /** Body of POST /api/emergencies/{id}/circle-alerts and /api/journeys/{id}/circle-alerts: 1 to 20 reports. */
    public record ReportBatch(
            @NotNull(message = "Reports are required")
            @Size(min = 1, max = 20, message = "Send between 1 and 20 reports")
            List<@NotNull(message = "A report must not be empty") @Valid Report> reports
    ) {
    }

    /** [stored] new reports, [updated] reports whose status moved forward; the rest were already known. */
    public record UploadResult(int received, int stored, int updated) {
    }
}
