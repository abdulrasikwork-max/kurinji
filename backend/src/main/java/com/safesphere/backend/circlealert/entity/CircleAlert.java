package com.safesphere.backend.circlealert.entity;

import com.safesphere.backend.circlealert.entity.CircleAlertEnums.Channel;
import com.safesphere.backend.circlealert.entity.CircleAlertEnums.Kind;
import com.safesphere.backend.circlealert.entity.CircleAlertEnums.SkipReason;
import com.safesphere.backend.circlealert.entity.CircleAlertEnums.Status;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One attempt to tell a Safety Circle contact something (Phase 4D, migration V8; N6 / V37: also the server's alert
 * queue). Two kinds of row:
 * <ul>
 *   <li>a PHONE row (SMS_AUTO / SMS_COMPOSER): what the phone reports it did; belongs to an emergency or (before N6) a
 *       journey;</li>
 *   <li>a SERVER row (SERVER_SMS, N6): a text the server queues and sends through the SMS provider; belongs to an
 *       emergency and its owner, or, for "Test my alerts" (kind TEST), only to its owner.</li>
 * </ul>
 * Holds no phone number (a server row keeps only an HMAC of the number it was sent to) and never the message text.
 * MySQL cannot CHECK the cascading foreign-key columns, so the factories guarantee who a row belongs to.
 */
@Entity
@Table(name = "circle_alerts")
public class CircleAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "emergency_id", updatable = false)
    private Long emergencyId;

    @Column(name = "journey_id", updatable = false)
    private Long journeyId;

    /** N6: the owner of a SERVER row (null on phone rows). */
    @Column(name = "user_id", updatable = false)
    private Long userId;

    /** Null when the contact was deleted later (ON DELETE SET NULL), never known, or a TEST row. */
    @Column(name = "contact_id")
    private Long contactId;

    /** N6: HMAC (hex) of the number a SERVER row was (or will be) sent to; never the number. */
    @Column(name = "recipient_key", length = 64)
    private String recipientKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 30)
    private Kind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private Channel channel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "client_ref", nullable = false, updatable = false, length = 36)
    private String clientRef;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private LocalDateTime occurredAt;

    @Column(name = "received_at", nullable = false, updatable = false)
    private LocalDateTime receivedAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** N6: the text that went out carried a map link. */
    @Column(name = "included_location", nullable = false)
    private boolean includedLocation;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at")
    private LocalDateTime nextAttemptAt;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Column(name = "provider_ref", length = 64)
    private String providerRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "skip_reason", length = 30)
    private SkipReason skipReason;

    /** Required by JPA. */
    protected CircleAlert() {
    }

    private CircleAlert(Long emergencyId, Long journeyId, Long userId, Long contactId, Kind kind, Channel channel,
                        Status status, String clientRef, LocalDateTime occurredAt, LocalDateTime now) {
        this.emergencyId = emergencyId;
        this.journeyId = journeyId;
        this.userId = userId;
        this.contactId = contactId;
        this.kind = kind;
        this.channel = channel;
        this.status = status;
        this.clientRef = clientRef;
        this.occurredAt = occurredAt;
        this.receivedAt = now;
        this.updatedAt = now;
    }

    /** What the phone reports about one of its own attempts for an emergency. */
    public static CircleAlert forEmergency(long emergencyId, Long contactId, Kind kind, Channel channel, Status status,
                                           String clientRef, LocalDateTime occurredAt, LocalDateTime now) {
        if (!channel.isPhone() || !status.isPhone() || kind == Kind.TEST) {
            throw new IllegalArgumentException("A phone report has a phone channel and status");
        }
        return new CircleAlert(emergencyId, null, null, contactId, kind, channel, status, clientRef, occurredAt, now);
    }

    /** N6: a text the server will send to a contact about an SOS: QUEUED until [dueAt]. */
    public static CircleAlert queued(long emergencyId, long userId, long contactId, Kind kind, LocalDateTime dueAt,
                                     LocalDateTime now) {
        if (kind == Kind.TEST || kind.isForJourney()) throw new IllegalArgumentException("Not an SOS alert kind");
        CircleAlert alert = new CircleAlert(emergencyId, null, userId, contactId, kind, Channel.SERVER_SMS,
                Status.QUEUED, UUID.randomUUID().toString(), now, now);
        alert.nextAttemptAt = dueAt;
        return alert;
    }

    /** N6: a contact the server will not text about an SOS (the reason is shown to the person as "text them yourself"). */
    public static CircleAlert skipped(long emergencyId, long userId, long contactId, Kind kind, SkipReason reason,
                                      LocalDateTime now) {
        CircleAlert alert = new CircleAlert(emergencyId, null, userId, contactId, kind, Channel.SERVER_SMS,
                Status.SKIPPED, UUID.randomUUID().toString(), now, now);
        alert.skipReason = reason;
        return alert;
    }

    /** N6: "Test my alerts": a text to the person's own number, due now. */
    public static CircleAlert test(long userId, LocalDateTime now) {
        CircleAlert alert = new CircleAlert(null, null, userId, null, Kind.TEST, Channel.SERVER_SMS, Status.QUEUED,
                UUID.randomUUID().toString(), now, now);
        alert.nextAttemptAt = now;
        return alert;
    }

    /** Phone rows: status only moves forward (e.g. SENT -> DELIVERED). Returns true when it changed. */
    public boolean advanceTo(Status newStatus, LocalDateTime now) {
        if (newStatus.rank() <= status.rank()) return false;
        this.status = newStatus;
        this.updatedAt = now;
        return true;
    }

    /** N6: due at once (the first location arrived before the 20 s were over). */
    public void dueNow(LocalDateTime now) {
        if (status != Status.QUEUED) return;
        if (nextAttemptAt.isAfter(now)) {
            this.nextAttemptAt = now;
            this.updatedAt = now;
        }
    }

    /**
     * N6: the dispatcher takes this row for one attempt. The next attempt is pushed back first ([holdUntil]), so a crash
     * between the provider's answer and [sent] retries at worst once more, later, instead of never.
     */
    public void claim(String recipientKey, boolean withLocation, LocalDateTime holdUntil, LocalDateTime now) {
        this.recipientKey = recipientKey;
        this.includedLocation = withLocation;
        this.attempts = attempts + 1;
        this.nextAttemptAt = holdUntil;
        this.updatedAt = now;
    }

    public void sent(String providerRef, LocalDateTime now) {
        this.status = Status.SENT;
        this.sentAt = now;
        this.providerRef = providerRef;
        this.nextAttemptAt = null;
        this.updatedAt = now;
    }

    /** N6: the provider refused; [retryAt] = null means no more attempts (FAILED). */
    public void refused(LocalDateTime retryAt, LocalDateTime now) {
        if (retryAt == null) {
            this.status = Status.FAILED;
            this.nextAttemptAt = null;
        } else {
            this.nextAttemptAt = retryAt;
        }
        this.updatedAt = now;
    }

    public void skip(SkipReason reason, String recipientKey, LocalDateTime now) {
        this.status = Status.SKIPPED;
        this.skipReason = reason;
        if (recipientKey != null) this.recipientKey = recipientKey;
        this.nextAttemptAt = null;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public Long getEmergencyId() {
        return emergencyId;
    }

    public Long getJourneyId() {
        return journeyId;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getContactId() {
        return contactId;
    }

    public String getRecipientKey() {
        return recipientKey;
    }

    public Kind getKind() {
        return kind;
    }

    public Channel getChannel() {
        return channel;
    }

    public Status getStatus() {
        return status;
    }

    public String getClientRef() {
        return clientRef;
    }

    public LocalDateTime getOccurredAt() {
        return occurredAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public boolean isIncludedLocation() {
        return includedLocation;
    }

    public int getAttempts() {
        return attempts;
    }

    public LocalDateTime getNextAttemptAt() {
        return nextAttemptAt;
    }

    public LocalDateTime getSentAt() {
        return sentAt;
    }

    public SkipReason getSkipReason() {
        return skipReason;
    }

    /** Ids and states only: a stray log line never holds a number key or a provider reference. */
    @Override
    public String toString() {
        return "CircleAlert{id=" + id + ", kind=" + kind + ", channel=" + channel + ", status=" + status + "}";
    }
}
