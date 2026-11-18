package com.safesphere.backend.circlealert.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/** N6 (D20): request / response bodies of the automatic Safety Circle texts. No number and no text of a contact. */
public final class AlertDtos {

    private AlertDtos() {
    }

    /**
     * GET / PUT /api/safety-alerts. [choice] UNSET (never chosen: nothing is sent), ON, OFF. [preview] = the exact first
     * text with the person's own name and number (the place shown as "(where you are)"). [contacts] = what would happen
     * for each enabled contact. [lastTest] = the newest "Test my alerts", or null.
     */
    public record Settings(String choice, Instant decidedAt, String preview, List<ContactPlan> contacts, Test lastTest,
                           boolean testAvailable) {
    }

    /** [automatic] true = IRL texts this contact in an SOS; otherwise [reason] and the person texts them by hand. */
    public record ContactPlan(long contactId, String name, boolean automatic, String reason) {
    }

    /** "Test my alerts": QUEUED / SENT / DELIVERED / FAILED / SKIPPED, and when the state was reached. */
    public record Test(String status, Instant at, String reason) {
    }

    /** Body of PUT /api/safety-alerts. */
    public record Choice(@NotNull(message = "Choose on or off") Boolean on) {
    }

    /**
     * GET /api/emergencies/{id}/alerts: what the server did for each contact. Unknown statuses must be read by the phone as
     * "not confirmed". [late] = texted as "sent late".
     */
    public record EmergencyAlerts(String choice, List<AlertState> alerts) {
    }

    /**
     * One server text. [kind] SOS_STARTED / SOS_LOCATION / SOS_RESOLVED / SOS_CANCELLED; [status] QUEUED / SENT (accepted
     * by the provider, NOT delivered) / DELIVERED / FAILED / SKIPPED; [reason] only when SKIPPED (STOPPED is shown as
     * NOT_AVAILABLE: the person never learns that a contact asked IRL for no alerts); [at] = when it was accepted, or
     * the time of the latest change.
     */
    public record AlertState(Long contactId, String contactName, String kind, String status, String reason, Instant at,
                             boolean late) {
    }

    /** Body of the admin stop-list routes. The number is used for its HMAC only and never stored or logged. */
    public record StopRequest(
            @NotBlank(message = "Enter a phone number")
            @Size(max = 20, message = "Enter a phone number")
            String phoneNumber) {

        @Override
        public String toString() {
            return "StopRequest{phoneNumber=<redacted>}";
        }
    }

    public record StopState(boolean stopped) {
    }
}
