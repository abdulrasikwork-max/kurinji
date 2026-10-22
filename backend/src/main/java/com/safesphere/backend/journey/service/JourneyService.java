package com.safesphere.backend.journey.service;

import com.safesphere.backend.common.exception.ConflictException;
import com.safesphere.backend.common.exception.InvalidRequestException;
import com.safesphere.backend.common.exception.ResourceNotFoundException;
import com.safesphere.backend.emergency.entity.EmergencyEvent;
import com.safesphere.backend.emergency.entity.EmergencyStatus;
import com.safesphere.backend.emergency.repository.EmergencyEventRepository;
import com.safesphere.backend.escalation.entity.EscalationEvent;
import com.safesphere.backend.escalation.entity.EscalationLevel;
import com.safesphere.backend.escalation.entity.EscalationReason;
import com.safesphere.backend.escalation.repository.EscalationEventRepository;
import com.safesphere.backend.journey.dto.JourneyLocationRequest;
import com.safesphere.backend.journey.dto.JourneyResponse;
import com.safesphere.backend.journey.dto.StartJourneyRequest;
import com.safesphere.backend.journey.entity.JourneyEvent;
import com.safesphere.backend.journey.entity.JourneyEventType;
import com.safesphere.backend.journey.entity.JourneyStatus;
import com.safesphere.backend.journey.entity.SafeJourney;
import com.safesphere.backend.journey.repository.JourneyEventRepository;
import com.safesphere.backend.journey.repository.SafeJourneyRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Safe Walk (Phase 4B). The server stores journeys, validates every state change, enforces ownership and
 * computes the check-in deadlines with its own clock. The PHONE runs the timer and the escalation policy
 * (it works offline) and reports what it reached; the server checks those reports are plausible.
 *
 * Nothing here contacts anyone. Level 2 is recorded; since Phase 4D the PHONE may text the Safety Circle (opt-in)
 * and reports that to CircleAlertService. Level 4 belongs to an emergency (EmergencyService.recordDialPrompt).
 */
@Service
public class JourneyService {

    private static final String NOT_FOUND = "Safe Walk not found";
    private static final String ALREADY_ENDED = "This Safe Walk has already ended";
    /** Same tolerance as emergency locations: how far the phone's clock may be off. */
    static final Duration CLOCK_TOLERANCE = Duration.ofMinutes(5);
    /**
     * How early a "check-in missed" report may arrive. Small on purpose: the phone counts down from the
     * server's own deadline (using serverTime), so only network delay has to be allowed for, not clock drift.
     */
    static final Duration MISSED_REPORT_TOLERANCE = Duration.ofSeconds(30);

    private final SafeJourneyRepository journeyRepository;
    private final JourneyEventRepository eventRepository;
    private final EscalationEventRepository escalationRepository;
    private final EmergencyEventRepository emergencyRepository;
    private final Clock clock;

    public JourneyService(SafeJourneyRepository journeyRepository, JourneyEventRepository eventRepository,
                          EscalationEventRepository escalationRepository, EmergencyEventRepository emergencyRepository,
                          Clock clock) {
        this.journeyRepository = journeyRepository;
        this.eventRepository = eventRepository;
        this.escalationRepository = escalationRepository;
        this.emergencyRepository = emergencyRepository;
        this.clock = clock;
    }

    @Transactional
    public JourneyResponse start(long userId, StartJourneyRequest request) {
        if (journeyRepository.existsByUserIdAndStatus(userId, JourneyStatus.ACTIVE)) {
            throw new ConflictException("A Safe Walk is already active");
        }
        if (emergencyRepository.existsByUserIdAndStatus(userId, EmergencyStatus.ACTIVE)) {
            throw new ConflictException("An emergency is active. End it before starting a Safe Walk");
        }
        BigDecimal latitude = request.destinationLatitude();
        BigDecimal longitude = request.destinationLongitude();
        if ((latitude == null) != (longitude == null)) {
            throw new InvalidRequestException("Destination latitude and longitude must be sent together");
        }
        LocalDateTime now = now();
        SafeJourney journey;
        try {
            // The database also allows only one ACTIVE journey per user, which covers two requests at once.
            journey = journeyRepository.saveAndFlush(new SafeJourney(
                    userId, cleanLabel(request.destinationLabel()),
                    latitude == null ? null : latitude.setScale(6, RoundingMode.HALF_UP),
                    longitude == null ? null : longitude.setScale(6, RoundingMode.HALF_UP),
                    request.checkInIntervalMinutes(), now));
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("A Safe Walk is already active");
        }
        eventRepository.save(new JourneyEvent(journey.getId(), JourneyEventType.STARTED, now));
        return respond(journey);
    }

    @Transactional(readOnly = true)
    public List<JourneyResponse> history(long userId) {
        Instant serverTime = Instant.now(clock);
        return journeyRepository.findTop20ByUserIdOrderByStartedAtDescIdDesc(userId).stream()
                .map(journey -> JourneyResponse.from(journey, serverTime))
                .toList();
    }

    @Transactional(readOnly = true)
    public JourneyResponse get(long userId, long journeyId) {
        return respond(journeyRepository.findByIdAndUserId(journeyId, userId)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND)));
    }

    /** "I'm OK": back to level 0 and a new deadline one interval from now (server time). Allowed at any time. */
    @Transactional
    public JourneyResponse checkIn(long userId, long journeyId) {
        SafeJourney journey = findActiveForUpdate(userId, journeyId);
        LocalDateTime now = now();
        if (journey.getEscalationLevel() != EscalationLevel.NORMAL) {
            escalationRepository.save(EscalationEvent.forJourney(
                    journey.getId(), EscalationLevel.NORMAL, EscalationReason.CHECKED_IN, now));
        }
        journey.checkIn(now);
        eventRepository.save(new JourneyEvent(journey.getId(), JourneyEventType.CHECKED_IN, now));
        return respond(journeyRepository.saveAndFlush(journey));
    }

    /** "I've arrived". */
    @Transactional
    public JourneyResponse complete(long userId, long journeyId) {
        return end(userId, journeyId, JourneyStatus.COMPLETED, JourneyEventType.COMPLETED);
    }

    @Transactional
    public JourneyResponse cancel(long userId, long journeyId) {
        return end(userId, journeyId, JourneyStatus.CANCELLED, JourneyEventType.CANCELLED);
    }

    /**
     * Records the level the phone's policy reached. Rules of the ladder:
     * never skip a level; 1 for a missed check-in only once the server's own deadline has passed; 3 only through
     * a confirmed SOS; 4 not available in this version; 5 reserved. Re-sending a level already reached is
     * accepted (no change), so an offline phone can safely retry.
     *
     * Phase 4C: level 1 may also be a "minor concern" seen by the phone (ROUTE_DEVIATION, UNEXPECTED_STOP). Those
     * can come before the deadline (that is their point), but they still only prompt the person: the ladder is
     * the same, and the next step (2) still needs no response. ROUTE_DEVIATION needs a walk with a destination.
     */
    @Transactional
    public JourneyResponse escalate(long userId, long journeyId, int requestedLevel, String requestedReason) {
        EscalationLevel requested;
        try {
            requested = EscalationLevel.fromCode(requestedLevel);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("Unknown escalation level");
        }
        EscalationReason reason = reasonFor(requested, requestedReason);
        switch (requested) {
            case EMERGENCY -> throw new InvalidRequestException("Level 3 is an emergency: start SOS to escalate");
            case EMERGENCY_PATHWAY -> throw new InvalidRequestException("Level 4 is the emergency pathway: start SOS first");
            case VERIFIED_NEARBY_ASSISTANCE -> throw new InvalidRequestException("Verified Nearby Assistance is reserved and not available");
            default -> { }
        }
        SafeJourney journey = findActiveForUpdate(userId, journeyId);
        int current = journey.getEscalationLevel().code();
        if (requested.code() <= current) {
            return respond(journey); // already there: idempotent
        }
        if (requested.code() > current + 1) {
            throw new InvalidRequestException("Escalation levels cannot be skipped");
        }
        LocalDateTime now = now();
        if (reason == EscalationReason.CHECK_IN_MISSED
                && now.isBefore(journey.getNextCheckInDueAt().minus(MISSED_REPORT_TOLERANCE))) {
            throw new ConflictException("The check-in is not due yet");
        }
        if (reason == EscalationReason.ROUTE_DEVIATION && !journey.hasDestination()) {
            throw new InvalidRequestException("A route check needs a Safe Walk with a destination");
        }
        journey.escalateTo(requested, now);
        escalationRepository.save(EscalationEvent.forJourney(journey.getId(), requested, reason, now));
        return respond(journeyRepository.saveAndFlush(journey));
    }

    /** Replaces the last known location. An older fix than the stored one is ignored (late, out-of-order upload). */
    @Transactional
    public void updateLocation(long userId, long journeyId, JourneyLocationRequest request) {
        SafeJourney journey = findActiveForUpdate(userId, journeyId);
        LocalDateTime now = now();
        LocalDateTime recordedAt = LocalDateTime.ofInstant(request.recordedAt().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
        if (recordedAt.isAfter(now.plus(CLOCK_TOLERANCE))) {
            throw new InvalidRequestException("Location time cannot be in the future");
        }
        if (recordedAt.isBefore(journey.getStartedAt().minus(CLOCK_TOLERANCE))) {
            throw new InvalidRequestException("Location time is before the Safe Walk started");
        }
        if (journey.getLastLocationAt() != null && !recordedAt.isAfter(journey.getLastLocationAt())) {
            return;
        }
        journey.recordLocation(
                request.latitude().setScale(6, RoundingMode.HALF_UP),
                request.longitude().setScale(6, RoundingMode.HALF_UP),
                request.accuracyMeters() == null ? null : request.accuracyMeters().setScale(1, RoundingMode.HALF_UP),
                recordedAt, now);
        journeyRepository.saveAndFlush(journey);
    }

    /**
     * Called by EmergencyService when an emergency starts. If the person is on a Safe Walk, the walk ends as
     * EMERGENCY, the emergency points to it, and level 3 (SOS confirmed) is recorded for both. The journey is
     * found by the authenticated user, never by an id sent by the client.
     */
    @Transactional
    public void linkActiveJourney(long userId, EmergencyEvent emergency) {
        journeyRepository.findByUserIdAndStatusForUpdate(userId, JourneyStatus.ACTIVE).ifPresent(journey -> {
            // Phase 11: an SOS recorded late (started offline) belongs only to a walk that was already running then.
            if (emergency.isStartedOffline() && journey.getStartedAt().isAfter(emergency.getStartedAt())) return;
            LocalDateTime now = now();
            journey.escalateTo(EscalationLevel.EMERGENCY, now);
            journey.end(JourneyStatus.EMERGENCY, now);
            journeyRepository.save(journey);
            eventRepository.save(new JourneyEvent(journey.getId(), JourneyEventType.EMERGENCY, now));
            escalationRepository.save(EscalationEvent.forJourneyAndEmergency(
                    journey.getId(), emergency.getId(), EscalationLevel.EMERGENCY, EscalationReason.SOS_CONFIRMED, now));
            emergency.linkJourney(journey.getId());
        });
    }

    private JourneyResponse end(long userId, long journeyId, JourneyStatus finalStatus, JourneyEventType eventType) {
        SafeJourney journey = findActiveForUpdate(userId, journeyId);
        LocalDateTime now = now();
        journey.end(finalStatus, now);
        eventRepository.save(new JourneyEvent(journey.getId(), eventType, now));
        return respond(journeyRepository.saveAndFlush(journey));
    }

    /** Level 1: missed check-in (default) or a phone-detected concern. Level 2: always "no response". */
    private static EscalationReason reasonFor(EscalationLevel level, String requested) {
        if (requested == null) {
            return level == EscalationLevel.CHECK_IN_MISSED ? EscalationReason.CHECK_IN_MISSED : EscalationReason.NO_RESPONSE;
        }
        EscalationReason reason;
        try {
            reason = EscalationReason.valueOf(requested);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("Unknown escalation reason");
        }
        boolean allowed = switch (level) {
            case CHECK_IN_MISSED -> reason == EscalationReason.CHECK_IN_MISSED
                    || reason == EscalationReason.ROUTE_DEVIATION
                    || reason == EscalationReason.UNEXPECTED_STOP;
            case NO_RESPONSE -> reason == EscalationReason.NO_RESPONSE;
            default -> false;
        };
        if (!allowed) {
            throw new InvalidRequestException("This reason does not match the escalation level");
        }
        return reason;
    }

    private SafeJourney findActiveForUpdate(long userId, long journeyId) {
        SafeJourney journey = journeyRepository.findByIdAndUserIdForUpdate(journeyId, userId)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND));
        if (journey.getStatus() != JourneyStatus.ACTIVE) {
            throw new ConflictException(ALREADY_ENDED);
        }
        return journey;
    }

    private JourneyResponse respond(SafeJourney journey) {
        return JourneyResponse.from(journey, Instant.now(clock));
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(Instant.now(clock), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    private static String cleanLabel(String label) {
        if (label == null) return null;
        String trimmed = label.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
