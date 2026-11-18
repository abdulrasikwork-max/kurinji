package com.safesphere.backend.circlealert.service;

import com.safesphere.backend.circlealert.dto.CircleAlertDtos.Report;
import com.safesphere.backend.circlealert.dto.CircleAlertDtos.UploadResult;
import com.safesphere.backend.circlealert.entity.CircleAlert;
import com.safesphere.backend.circlealert.entity.CircleAlertEnums.Kind;
import com.safesphere.backend.circlealert.repository.CircleAlertRepository;
import com.safesphere.backend.common.exception.ConflictException;
import com.safesphere.backend.common.exception.InvalidRequestException;
import com.safesphere.backend.common.exception.ResourceNotFoundException;
import com.safesphere.backend.emergency.entity.EmergencyEvent;
import com.safesphere.backend.emergency.repository.EmergencyEventRepository;
import com.safesphere.backend.safetycontact.repository.SafetyContactRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 4D: records what the PHONE reports about telling the Safety Circle. The server sends nothing itself (there is
 * no SMS gateway); it keeps an honest log for the person and the Emergency Context Packet.
 *
 * Rules: the emergency must belong to the caller (else 404); only SOS kinds (N6-B1: walks no longer report alerts);
 * times are checked against the SERVER clock with a 5-minute tolerance; a contact id that is not the caller's is
 * stored as "unknown" (never linked, never revealed); a batch is all-or-nothing; re-sending a report is harmless and a
 * status only moves forward; at most [MAX_PER_TARGET] per emergency.
 */
@Service
public class CircleAlertService {

    static final Duration CLOCK_TOLERANCE = Duration.ofMinutes(5);
    static final int MAX_PER_TARGET = 200;

    private final CircleAlertRepository alertRepository;
    private final EmergencyEventRepository emergencyRepository;
    private final SafetyContactRepository contactRepository;
    private final Clock clock;

    public CircleAlertService(CircleAlertRepository alertRepository, EmergencyEventRepository emergencyRepository,
                              SafetyContactRepository contactRepository, Clock clock) {
        this.alertRepository = alertRepository;
        this.emergencyRepository = emergencyRepository;
        this.contactRepository = contactRepository;
        this.clock = clock;
    }

    @Transactional
    public UploadResult uploadForEmergency(long userId, long emergencyId, List<Report> reports) {
        // Locked: two uploads for one emergency are stored one after the other (limit stays correct).
        EmergencyEvent emergency = emergencyRepository.findByIdAndUserIdForUpdate(emergencyId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Emergency not found"));
        return upload(reports, emergency.getStartedAt(), alertRepository.countByEmergencyId(emergencyId),
                existing -> emergencyId == existingId(existing.getEmergencyId()),
                (report, at, now) -> CircleAlert.forEmergency(emergencyId, ownContact(userId, report.contactId()),
                        report.kind(), report.channel(), report.status(), report.clientRef(), at, now));
    }

    @FunctionalInterface
    private interface AlertFactory {
        CircleAlert create(Report report, LocalDateTime occurredAt, LocalDateTime now);
    }

    private UploadResult upload(List<Report> reports, LocalDateTime startedAt,
                                long alreadyStored, java.util.function.Predicate<CircleAlert> sameTarget,
                                AlertFactory factory) {
        LocalDateTime now = LocalDateTime.ofInstant(Instant.now(clock), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);

        // 1. Validate every report before writing anything. A reference sent twice in one batch keeps its best status.
        Map<String, Report> byRef = new LinkedHashMap<>();
        Map<String, LocalDateTime> times = new LinkedHashMap<>();
        for (Report report : reports) {
            if (report.kind().isForJourney()) {
                throw new InvalidRequestException("This alert kind belongs to a Safe Walk, not an emergency");
            }
            if (!report.channel().isPhone() || !report.status().isPhone() || report.kind() == Kind.TEST) {
                // N6: only the server queues, skips or texts through the provider; a phone reports what IT did.
                throw new InvalidRequestException("A phone can only report what it did itself");
            }
            LocalDateTime at = LocalDateTime.ofInstant(report.occurredAt().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
            if (at.isAfter(now.plus(CLOCK_TOLERANCE))) {
                throw new InvalidRequestException("Alert time cannot be in the future");
            }
            if (at.isBefore(startedAt.minus(CLOCK_TOLERANCE))) {
                throw new InvalidRequestException("Alert time is before the start");
            }
            String ref = report.clientRef().toLowerCase();
            Report previous = byRef.get(ref);
            if (previous == null || report.status().rank() > previous.status().rank()) {
                byRef.put(ref, report);
                times.put(ref, at);
            }
        }

        // 2. Known references: same target -> status may move forward; another target -> refused.
        Map<String, CircleAlert> existing = alertRepository.findByClientRefIn(byRef.keySet()).stream()
                .collect(Collectors.toMap(a -> a.getClientRef().toLowerCase(), Function.identity()));
        for (CircleAlert alert : existing.values()) {
            if (!sameTarget.test(alert) || !alert.getChannel().isPhone()) {
                throw new ConflictException("This alert reference is already used");
            }
        }
        long newOnes = byRef.keySet().stream().filter(ref -> !existing.containsKey(ref)).count();
        if (alreadyStored + newOnes > MAX_PER_TARGET) {
            throw new ConflictException("Too many alerts are recorded for this emergency");
        }

        // 3. Write.
        int stored = 0;
        int updated = 0;
        for (Map.Entry<String, Report> entry : byRef.entrySet()) {
            CircleAlert known = existing.get(entry.getKey());
            if (known != null) {
                if (known.advanceTo(entry.getValue().status(), now)) {
                    alertRepository.save(known);
                    updated++;
                }
            } else {
                Report report = entry.getValue();
                alertRepository.save(factory.create(
                        new Report(entry.getKey(), report.contactId(), report.kind(), report.channel(), report.status(),
                                report.occurredAt()),
                        times.get(entry.getKey()), now));
                stored++;
            }
        }
        alertRepository.flush();
        return new UploadResult(reports.size(), stored, updated);
    }

    /** Only the caller's own contacts are linked; anything else (deleted, someone else's) is stored as unknown. */
    private Long ownContact(long userId, Long contactId) {
        if (contactId == null) return null;
        return contactRepository.findByIdAndUserId(contactId, userId).isPresent() ? contactId : null;
    }

    private static long existingId(Long id) {
        return id == null ? -1 : id;
    }

    /** Used by the Emergency Context Packet. The caller has already checked ownership. */
    @Transactional(readOnly = true)
    public List<CircleAlert> forEmergency(long emergencyId) {
        return alertRepository.findByEmergencyIdOrderByOccurredAtAscIdAsc(emergencyId);
    }
}
