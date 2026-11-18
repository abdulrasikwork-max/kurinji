package com.safesphere.backend.circlealert.service;

import com.safesphere.backend.auth.phone.PhoneNumbers;
import com.safesphere.backend.circlealert.entity.CircleAlert;
import com.safesphere.backend.circlealert.entity.CircleAlertEnums.Channel;
import com.safesphere.backend.circlealert.entity.CircleAlertEnums.Kind;
import com.safesphere.backend.circlealert.entity.CircleAlertEnums.SkipReason;
import com.safesphere.backend.circlealert.entity.CircleAlertEnums.Status;
import com.safesphere.backend.circlealert.repository.AlertSettingRepository;
import com.safesphere.backend.circlealert.repository.AlertStopRepository;
import com.safesphere.backend.circlealert.repository.CircleAlertRepository;
import com.safesphere.backend.circlealert.service.AlertPolicy.Facts;
import com.safesphere.backend.circlealert.service.AlertPolicy.Planned;
import com.safesphere.backend.circlealert.service.AlertPolicy.Position;
import com.safesphere.backend.circlealert.service.AlertPolicy.Recipient;
import com.safesphere.backend.config.AlertProperties;
import com.safesphere.backend.emergency.entity.EmergencyEvent;
import com.safesphere.backend.emergency.entity.EmergencyLocation;
import com.safesphere.backend.emergency.entity.EmergencyStatus;
import com.safesphere.backend.emergency.repository.EmergencyEventRepository;
import com.safesphere.backend.emergency.repository.EmergencyLocationRepository;
import com.safesphere.backend.safetycontact.entity.SafetyContact;
import com.safesphere.backend.safetycontact.repository.SafetyContactRepository;
import com.safesphere.backend.user.entity.User;
import com.safesphere.backend.user.repository.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * N6 (D20): the database side of the server's Safety Circle texts. Every step is its own short transaction, called by
 * [AlertDispatcher]; the SMS provider is called BETWEEN them, never inside one (Phase 14 rule: a slow or failing
 * provider never holds a lock or rolls back a record).
 *
 * <ol>
 *   <li>[plan]: queue the first text for each recent ACTIVE SOS of a person who chose automatic alerts (once per SOS);
 *       queue one location follow-up and the closing texts; mark texts of an SOS that ended before they went out as
 *       SKIPPED (a false alarm in the first 20 s sends nothing).</li>
 *   <li>[dueIds]: the queued rows whose time has come (or whose SOS now has its first location).</li>
 *   <li>[claim]: re-checks one row against fresh data (SOS still active, contact still there, Indian mobile, stop list,
 *       limits) and builds its text from the allow-listed facts, or skips it.</li>
 *   <li>[result]: SENT (with the provider's reference), a retry, or FAILED.</li>
 * </ol>
 * Nothing here logs a number, a text or a location.
 */
@Service
public class AlertQueue {

    /** How long a claimed row waits before it could be tried again if the process died during the provider call. */
    static final Duration CLAIM_HOLD = Duration.ofMinutes(2);
    static final Set<Status> ACCEPTED = Set.of(Status.SENT, Status.DELIVERED);
    private static final Set<Kind> CLOSINGS = Set.of(Kind.SOS_RESOLVED, Kind.SOS_CANCELLED);

    /** One text ready for the provider. */
    public record Outgoing(long alertId, String phoneE164, String text) {
        @Override
        public String toString() {
            return "Outgoing{alertId=" + alertId + "}";
        }
    }

    private final CircleAlertRepository alerts;
    private final AlertSettingRepository settings;
    private final AlertStopRepository stops;
    private final EmergencyEventRepository emergencies;
    private final EmergencyLocationRepository locations;
    private final SafetyContactRepository contacts;
    private final UserRepository users;
    private final AlertKeys keys;
    private final AlertProperties properties;

    public AlertQueue(CircleAlertRepository alerts, AlertSettingRepository settings, AlertStopRepository stops,
                      EmergencyEventRepository emergencies, EmergencyLocationRepository locations,
                      SafetyContactRepository contacts, UserRepository users, AlertKeys keys,
                      AlertProperties properties) {
        this.alerts = alerts;
        this.settings = settings;
        this.stops = stops;
        this.emergencies = emergencies;
        this.locations = locations;
        this.contacts = contacts;
        this.users = users;
        this.keys = keys;
        this.properties = properties;
    }

    // ---- 1. plan -----------------------------------------------------------------------------------------------------

    @Transactional
    public void plan(LocalDateTime now) {
        planFirstTexts(now);
        planFollowUpsAndClosings(now);
        skipTextsOfEndedSoses(now);
    }

    private void planFirstTexts(LocalDateTime now) {
        for (EmergencyEvent sos : emergencies.findByStatusAndCreatedAtAfter(EmergencyStatus.ACTIVE,
                now.minus(properties.staleAfter()))) {
            if (alerts.existsByEmergencyIdAndChannelAndKind(sos.getId(), Channel.SERVER_SMS, Kind.SOS_STARTED)) continue;
            if (!choseAutomaticAlerts(sos.getUserId())) continue;
            List<SafetyContact> enabled = enabledContacts(sos.getUserId());
            if (enabled.isEmpty()) continue;
            long userId = sos.getUserId();
            SkipReason everyone = null;
            if (AlertPolicy.tooLate(sos.getStartedAt(), sos.getCreatedAt(), properties.staleAfter())) {
                everyone = SkipReason.TOO_LATE;
            } else if (alerts.countRounds(userId, now.minusHours(24)) >= properties.roundsPerPerson()) {
                everyone = SkipReason.PERSON_LIMIT;
            }
            if (everyone != null) {
                for (SafetyContact c : enabled) {
                    alerts.save(CircleAlert.skipped(sos.getId(), userId, c.getId(), Kind.SOS_STARTED, everyone, now));
                }
                continue;
            }
            LocalDateTime due = AlertPolicy.firstDue(sos.getCreatedAt(), properties.sendDelay());
            for (Planned p : AlertPolicy.plan(recipients(enabled), stops::existsByNumberKey, properties.maxContacts())) {
                alerts.save(p.texted()
                        ? CircleAlert.queued(sos.getId(), userId, p.contactId(), Kind.SOS_STARTED, due, now)
                        : CircleAlert.skipped(sos.getId(), userId, p.contactId(), Kind.SOS_STARTED, p.reason(), now));
            }
        }
    }

    private void planFollowUpsAndClosings(LocalDateTime now) {
        Map<Long, EmergencyEvent> byId = new HashMap<>();
        for (CircleAlert first : alerts.findByChannelAndKindAndStatusInAndSentAtAfter(Channel.SERVER_SMS,
                Kind.SOS_STARTED, ACCEPTED, now.minus(properties.closingWindow()))) {
            if (first.getContactId() == null) continue; // the contact was deleted: nobody left to tell
            EmergencyEvent sos = byId.computeIfAbsent(first.getEmergencyId(),
                    id -> emergencies.findById(id).orElse(null));
            if (sos == null) continue;
            long emergencyId = sos.getId();
            if (sos.getStatus() == EmergencyStatus.ACTIVE) {
                boolean exists = alerts.existsByEmergencyIdAndContactIdAndKindIn(emergencyId, first.getContactId(),
                        Set.of(Kind.SOS_LOCATION));
                if (AlertPolicy.followUpDue(first.isIncludedLocation(), first.getSentAt(),
                        locations.countByEmergencyId(emergencyId) > 0, true, exists, properties.followUpWindow(), now)) {
                    alerts.save(CircleAlert.queued(emergencyId, first.getUserId(), first.getContactId(),
                            Kind.SOS_LOCATION, now, now));
                }
            } else {
                boolean exists = alerts.existsByEmergencyIdAndContactIdAndKindIn(emergencyId, first.getContactId(),
                        CLOSINGS);
                if (AlertPolicy.closingDue(true, sos.isEndedElsewhere(), exists)) {
                    Kind closing = sos.getStatus() == EmergencyStatus.RESOLVED ? Kind.SOS_RESOLVED : Kind.SOS_CANCELLED;
                    alerts.save(CircleAlert.queued(emergencyId, first.getUserId(), first.getContactId(), closing, now,
                            now));
                }
            }
        }
    }

    private void skipTextsOfEndedSoses(LocalDateTime now) {
        for (CircleAlert row : alerts.findTop100ByChannelAndStatusOrderByNextAttemptAtAscIdAsc(Channel.SERVER_SMS,
                Status.QUEUED)) {
            if (row.getKind() != Kind.SOS_STARTED && row.getKind() != Kind.SOS_LOCATION) continue;
            boolean active = emergencies.findById(row.getEmergencyId())
                    .map(e -> e.getStatus() == EmergencyStatus.ACTIVE).orElse(false);
            if (!active) {
                row.skip(SkipReason.ENDED, null, now);
                alerts.save(row);
            }
        }
    }

    // ---- 2. due ------------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Long> dueIds(LocalDateTime now) {
        List<Long> out = new ArrayList<>();
        for (CircleAlert row : alerts.findTop100ByChannelAndStatusOrderByNextAttemptAtAscIdAsc(Channel.SERVER_SMS,
                Status.QUEUED)) {
            boolean hasLocation = row.getEmergencyId() != null && locations.countByEmergencyId(row.getEmergencyId()) > 0;
            if (AlertPolicy.due(row.getKind(), row.getNextAttemptAt(), row.getAttempts(), hasLocation, now)) {
                out.add(row.getId());
            }
        }
        return out;
    }

    // ---- 3. claim ----------------------------------------------------------------------------------------------------

    @Transactional
    public Optional<Outgoing> claim(long alertId, LocalDateTime now) {
        CircleAlert row = alerts.findById(alertId).orElse(null);
        if (row == null || row.getStatus() != Status.QUEUED || row.getChannel() != Channel.SERVER_SMS) {
            return Optional.empty();
        }
        User owner = users.findById(row.getUserId()).orElse(null);
        if (owner == null || owner.isErased() || owner.getPhoneE164() == null) {
            return skip(row, SkipReason.CONTACT_GONE, null, now);
        }
        EmergencyEvent sos = row.getEmergencyId() == null ? null : emergencies.findById(row.getEmergencyId()).orElse(null);
        Kind kind = row.getKind();
        if (kind != Kind.TEST && sos == null) return skip(row, SkipReason.ENDED, null, now);
        if ((kind == Kind.SOS_STARTED || kind == Kind.SOS_LOCATION) && sos.getStatus() != EmergencyStatus.ACTIVE) {
            return skip(row, SkipReason.ENDED, null, now);
        }

        String number;
        if (kind == Kind.TEST) {
            number = owner.getPhoneE164();
        } else {
            SafetyContact contact = row.getContactId() == null ? null
                    : contacts.findByIdAndUserId(row.getContactId(), row.getUserId()).orElse(null);
            if (contact == null || !contact.isEnabled()) return skip(row, SkipReason.CONTACT_GONE, null, now);
            number = PhoneNumbers.normalize(contact.getPhoneNumber());
            if (number == null) return skip(row, SkipReason.NOT_INDIAN_MOBILE, null, now);
        }
        String key = keys.of(number);
        LocalDateTime dayAgo = now.minusHours(24);
        SkipReason limit = AlertPolicy.overLimit(stops.existsByNumberKey(key),
                alerts.countByRecipientKeyAndStatusInAndSentAtAfter(key, ACCEPTED, dayAgo), properties.perNumber(),
                alerts.countByChannelAndStatusInAndSentAtAfter(Channel.SERVER_SMS, ACCEPTED, dayAgo),
                properties.serverWide());
        if (limit != null) return skip(row, limit, key, now);

        Position position = null;
        Instant at = utc(now);
        boolean late = false;
        if (kind == Kind.SOS_STARTED || kind == Kind.SOS_LOCATION) {
            EmergencyLocation latest = locations.findFirstByEmergencyIdOrderByRecordedAtDescIdDesc(sos.getId())
                    .orElse(null);
            if (latest != null) position = new Position(latest.getLatitude(), latest.getLongitude());
            if (kind == Kind.SOS_LOCATION) {
                if (latest == null) return skip(row, SkipReason.ENDED, key, now); // cannot happen: planned with one
                at = utc(latest.getRecordedAt());
            } else {
                at = utc(sos.getStartedAt());
                late = AlertPolicy.late(sos.getStartedAt(), sos.getCreatedAt(), properties.lateAfter());
            }
        } else if (kind != Kind.TEST) {
            at = utc(sos.getEndedAt() == null ? now : sos.getEndedAt());
        }
        String text = AlertPolicy.text(kind, new Facts(owner.getName(), owner.getPhoneE164(), at, position, late));
        row.claim(key, position != null, now.plus(CLAIM_HOLD), now);
        alerts.save(row);
        return Optional.of(new Outgoing(row.getId(), number, text));
    }

    // ---- 4. result ---------------------------------------------------------------------------------------------------

    @Transactional
    public void result(long alertId, boolean accepted, String providerRef, LocalDateTime now) {
        alerts.findById(alertId).ifPresent(row -> {
            if (row.getStatus() != Status.QUEUED) return;
            if (accepted) row.sent(providerRef, now);
            else row.refused(AlertPolicy.retryAt(row.getAttempts(), properties.maxAttempts(), properties.retryDelay(),
                    now), now);
            alerts.save(row);
        });
    }

    // ---- shared ------------------------------------------------------------------------------------------------------

    boolean choseAutomaticAlerts(long userId) {
        return settings.findById(userId).map(s -> s.isServerSms()).orElse(false);
    }

    /** Every contact's name by id (also switched-off ones: an SOS may have texted them before). */
    Map<Long, String> contactNames(long userId) {
        Map<Long, String> names = new HashMap<>();
        contacts.findByUserIdOrderByPrimaryContactDescPriorityAscNameAsc(userId)
                .forEach(c -> names.put(c.getId(), c.getName()));
        return names;
    }

    /** The enabled contacts by priority (primary first), the order the planner texts them in. */
    List<SafetyContact> enabledContacts(long userId) {
        return contacts.findByUserIdOrderByPrimaryContactDescPriorityAscNameAsc(userId).stream()
                .filter(SafetyContact::isEnabled).toList();
    }

    List<Recipient> recipients(List<SafetyContact> inOrder) {
        return inOrder.stream().map(c -> {
            String number = PhoneNumbers.normalize(c.getPhoneNumber());
            return new Recipient(c.getId(), number, number == null ? null : keys.of(number));
        }).toList();
    }

    private Optional<Outgoing> skip(CircleAlert row, SkipReason reason, String key, LocalDateTime now) {
        row.skip(reason, key, now);
        alerts.save(row);
        return Optional.empty();
    }

    private static Instant utc(LocalDateTime time) {
        return time.toInstant(ZoneOffset.UTC);
    }
}
