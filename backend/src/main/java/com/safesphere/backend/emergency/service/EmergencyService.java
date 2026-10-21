package com.safesphere.backend.emergency.service;

import com.safesphere.backend.common.exception.ConflictException;
import com.safesphere.backend.common.exception.InvalidRequestException;
import com.safesphere.backend.common.exception.ResourceNotFoundException;
import com.safesphere.backend.emergency.dto.EmergencyResponse;
import com.safesphere.backend.emergency.entity.EmergencyEvent;
import com.safesphere.backend.emergency.entity.EmergencyStatus;
import com.safesphere.backend.emergency.entity.TriggerType;
import com.safesphere.backend.emergency.repository.EmergencyEventRepository;
import com.safesphere.backend.escalation.entity.EscalationEvent;
import com.safesphere.backend.escalation.entity.EscalationLevel;
import com.safesphere.backend.escalation.entity.EscalationReason;
import com.safesphere.backend.escalation.repository.EscalationEventRepository;
import com.safesphere.backend.journey.service.JourneyService;
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
 * Records emergency sessions. The SERVER sends nothing (no SMS gateway, push, call or recording); in Phase 4D the
 * PHONE may text the Safety Circle and reports it to CircleAlertService. Location points (Phase 4A) are handled by
 * EmergencyLocationService. Every method works on the caller's own rows only.
 */
@Service
public class EmergencyService {

    private static final String NOT_FOUND = "Emergency not found";

    private final EmergencyEventRepository repository;
    private final JourneyService journeyService;
    private final EscalationEventRepository escalationRepository;
    private final Clock clock;

    public EmergencyService(EmergencyEventRepository repository, JourneyService journeyService,
                            EscalationEventRepository escalationRepository, Clock clock) {
        this.repository = repository;
        this.journeyService = journeyService;
        this.escalationRepository = escalationRepository;
        this.clock = clock;
    }

    @Transactional
    public EmergencyResponse start(long userId, TriggerType requestedTrigger) {
        TriggerType trigger = requestedTrigger == null ? TriggerType.MANUAL : requestedTrigger;
        if (trigger != TriggerType.MANUAL) {
            throw new InvalidRequestException("Only manual emergencies are supported in this version");
        }
        if (repository.existsByUserIdAndStatus(userId, EmergencyStatus.ACTIVE)) {
            throw new ConflictException("An emergency is already active");
        }
        EmergencyEvent event;
        try {
            // The database also allows only one ACTIVE row per user, which covers two requests at once.
            event = repository.saveAndFlush(new EmergencyEvent(userId, trigger));
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("An emergency is already active");
        }
        // Phase 4B: an SOS during a Safe Walk ends the walk as EMERGENCY and links the two (level 3).
        journeyService.linkActiveJourney(userId, event);
        return EmergencyResponse.from(repository.saveAndFlush(event));
    }

    @Transactional(readOnly = true)
    public List<EmergencyResponse> history(long userId) {
        return repository.findTop50ByUserIdOrderByStartedAtDescIdDesc(userId).stream()
                .map(EmergencyResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public EmergencyResponse get(long userId, long emergencyId) {
        return EmergencyResponse.from(findOwned(userId, emergencyId));
    }

    /** "False alarm": ACTIVE -> CANCELLED. */
    @Transactional
    public EmergencyResponse cancel(long userId, long emergencyId) {
        return end(userId, emergencyId, EmergencyStatus.CANCELLED);
    }

    /** "I'm safe": ACTIVE -> RESOLVED. */
    @Transactional
    public EmergencyResponse resolve(long userId, long emergencyId) {
        return end(userId, emergencyId, EmergencyStatus.RESOLVED);
    }

    /**
     * Level 4, the emergency pathway (Phase 4D): the person tapped "Call 112" and the phone opened its dialer.
     * Recorded once per emergency (repeat taps change nothing); only while the emergency is ACTIVE; never skips a
     * level (level 3 is the active emergency itself). The app cannot know whether a call was made, so nothing
     * here claims it.
     */
    @Transactional
    public void recordDialPrompt(long userId, long emergencyId) {
        EmergencyEvent event = repository.findByIdAndUserIdForUpdate(emergencyId, userId)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND));
        if (event.getStatus() != EmergencyStatus.ACTIVE) {
            throw new ConflictException("This emergency has already ended");
        }
        if (!escalationRepository.existsByEmergencyIdAndLevel(emergencyId, EscalationLevel.EMERGENCY_PATHWAY.code())) {
            LocalDateTime now = LocalDateTime.ofInstant(Instant.now(clock), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
            escalationRepository.save(EscalationEvent.forEmergency(
                    emergencyId, EscalationLevel.EMERGENCY_PATHWAY, EscalationReason.DIAL_PROMPT_OPENED, now));
        }
    }

    private EmergencyResponse end(long userId, long emergencyId, EmergencyStatus finalStatus) {
        EmergencyEvent event = findOwned(userId, emergencyId);
        if (event.getStatus() != EmergencyStatus.ACTIVE) {
            throw new ConflictException("This emergency has already ended");
        }
        event.end(finalStatus);
        return EmergencyResponse.from(repository.saveAndFlush(event));
    }

    private EmergencyEvent findOwned(long userId, long emergencyId) {
        return repository.findByIdAndUserId(emergencyId, userId)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND));
    }
}
