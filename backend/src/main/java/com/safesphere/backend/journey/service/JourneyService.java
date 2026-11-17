package com.safesphere.backend.journey.service;

import com.safesphere.backend.common.exception.ConflictException;
import com.safesphere.backend.common.exception.ResourceNotFoundException;
import com.safesphere.backend.emergency.entity.EmergencyEvent;
import com.safesphere.backend.emergency.entity.EmergencyStatus;
import com.safesphere.backend.emergency.repository.EmergencyEventRepository;
import com.safesphere.backend.escalation.entity.EscalationEvent;
import com.safesphere.backend.escalation.entity.EscalationLevel;
import com.safesphere.backend.escalation.entity.EscalationReason;
import com.safesphere.backend.escalation.repository.EscalationEventRepository;
import com.safesphere.backend.journey.dto.JourneyResponse;
import com.safesphere.backend.journey.dto.StartJourneyRequest;
import com.safesphere.backend.journey.entity.JourneyEvent;
import com.safesphere.backend.journey.entity.JourneyEventType;
import com.safesphere.backend.journey.entity.JourneyStatus;
import com.safesphere.backend.journey.entity.SafeJourney;
import com.safesphere.backend.journey.repository.JourneyEventRepository;
import com.safesphere.backend.journey.repository.SafeJourneyRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "On my way" (N6 D22; code name Safe Walk, Phase 4B). The server stores walks, validates every state change, enforces
 * ownership and computes the reminder deadlines with its own clock. The PHONE schedules the reminder.
 *
 * N6-B1: a reminder, not tracking. No location, no destination, no route / stop checks and no level 1 / 2 reports
 * (those endpoints are gone). Nothing here contacts anyone. Kept: start, get, history, check-in ("More time"),
 * complete ("I'm there"), cancel, and the SOS link (an SOS during a walk ends it as EMERGENCY, level 3).
 */
@Service
public class JourneyService {

    private static final String NOT_FOUND = "Safe Walk not found";
    private static final String ALREADY_ENDED = "This Safe Walk has already ended";

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
        LocalDateTime now = now();
        SafeJourney journey;
        try {
            // The database also allows only one ACTIVE journey per user, which covers two requests at once.
            journey = journeyRepository.saveAndFlush(new SafeJourney(
                    userId, cleanLabel(request.destinationLabel()), request.checkInIntervalMinutes(), now));
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

    /**
     * "More time": a new deadline one interval from now (server time). Allowed at any time. A walk left on level 1 / 2
     * by a phone from before N6 is brought back to 0, and that is recorded, as before.
     */
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

    /** "I'm there". */
    @Transactional
    public JourneyResponse complete(long userId, long journeyId) {
        return end(userId, journeyId, JourneyStatus.COMPLETED, JourneyEventType.COMPLETED);
    }

    @Transactional
    public JourneyResponse cancel(long userId, long journeyId) {
        return end(userId, journeyId, JourneyStatus.CANCELLED, JourneyEventType.CANCELLED);
    }

    /**
     * Called by EmergencyService when an emergency starts. If the person is on a walk, the walk ends as EMERGENCY, the
     * emergency points to it, and level 3 (SOS confirmed) is recorded for both. The walk is found by the authenticated
     * user, never by an id sent by the client.
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
