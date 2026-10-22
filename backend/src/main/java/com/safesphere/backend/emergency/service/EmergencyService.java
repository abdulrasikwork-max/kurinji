package com.safesphere.backend.emergency.service;

import com.safesphere.backend.account.service.PasswordGuard;
import com.safesphere.backend.common.exception.ConflictException;
import com.safesphere.backend.common.exception.ForbiddenException;
import com.safesphere.backend.user.entity.User;
import com.safesphere.backend.user.repository.UserRepository;
import com.safesphere.backend.common.exception.InvalidRequestException;
import com.safesphere.backend.common.exception.ResourceNotFoundException;
import com.safesphere.backend.emergency.dto.CreateEmergencyRequest;
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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.regex.Pattern;
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
    private final UserRepository users;
    private final PasswordGuard passwords;

    public EmergencyService(EmergencyEventRepository repository, JourneyService journeyService,
                            EscalationEventRepository escalationRepository, Clock clock, UserRepository users,
                            PasswordGuard passwords) {
        this.repository = repository;
        this.journeyService = journeyService;
        this.escalationRepository = escalationRepository;
        this.clock = clock;
        this.users = users;
        this.passwords = passwords;
    }

    @Transactional
    public EmergencyResponse start(long userId, TriggerType requestedTrigger) {
        return start(userId, requestedTrigger, null);
    }

    /** Phase 10A.4: [sessionId] = the sign-in starting the SOS; only it may end the SOS without the password. */
    @Transactional
    public EmergencyResponse start(long userId, TriggerType requestedTrigger, String sessionId) {
        return start(userId, new CreateEmergencyRequest(requestedTrigger), sessionId);
    }

    /**
     * Phase 11 (SOS without internet). The phone starts every SOS by itself and sends this request with its own
     * reference ([CreateEmergencyRequest#clientRef]); the same reference always answers with the same emergency, so a
     * lost answer or a retry after hours offline never creates a second one. An SOS that began before the server could
     * be reached carries the phone's start time (clamped: never in the future, at most [MAX_OFFLINE_AGE] back); one that
     * also ended offline carries its outcome and end time and is stored as already ended.
     */
    @Transactional
    public EmergencyResponse start(long userId, CreateEmergencyRequest request, String sessionId) {
        TriggerType trigger = request.triggerType() == null ? TriggerType.MANUAL : request.triggerType();
        if (trigger != TriggerType.MANUAL) {
            throw new InvalidRequestException("Only manual emergencies are supported in this version");
        }
        String clientRef = request.clientRef();
        if (clientRef != null && !CLIENT_REF.matcher(clientRef).matches()) {
            throw new InvalidRequestException("clientRef must be a lower-case UUID");
        }
        boolean offline = request.startedAt() != null;
        EmergencyStatus outcome = request.outcome();
        if (offline && clientRef == null) {
            throw new InvalidRequestException("An SOS started offline needs a clientRef");
        }
        if (outcome == EmergencyStatus.ACTIVE) {
            throw new InvalidRequestException("outcome must be RESOLVED or CANCELLED");
        }
        if ((outcome == null) != (request.endedAt() == null) || (outcome != null && !offline)) {
            throw new InvalidRequestException("An SOS that ended offline needs startedAt, endedAt and outcome");
        }
        if (clientRef != null) {
            // A re-sent request: answer with what was stored the first time (it may have ended since).
            var known = repository.findByUserIdAndClientRef(userId, clientRef);
            if (known.isPresent()) {
                return EmergencyResponse.from(known.get());
            }
        }
        if (outcome == null && repository.existsByUserIdAndStatus(userId, EmergencyStatus.ACTIVE)) {
            throw new ConflictException("An emergency is already active");
        }
        LocalDateTime now = LocalDateTime.ofInstant(Instant.now(clock), ZoneOffset.UTC);
        // Online: the server's own time, exactly as before Phase 11. Offline: the phone's, clamped.
        LocalDateTime startedAt = offline
                ? clamp(toUtc(request.startedAt()), now.minus(MAX_OFFLINE_AGE), now)
                : LocalDateTime.now(ZoneOffset.UTC);
        // An SOS that already ended keeps no session id (V17): nobody can end it any more.
        EmergencyEvent event = new EmergencyEvent(userId, trigger, outcome == null ? sessionId : null, startedAt,
                clientRef, offline);
        if (outcome != null) {
            event.end(outcome, false, clamp(toUtc(request.endedAt()), startedAt, now));
        }
        try {
            // The database also allows only one ACTIVE row per user (and one row per reference), which covers two
            // requests at once. The phone retries a conflict on a reference and then gets the stored row.
            event = repository.saveAndFlush(event);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("An emergency is already active");
        }
        // Phase 4B: an SOS during a Safe Walk ends the walk as EMERGENCY and links the two (level 3).
        journeyService.linkActiveJourney(userId, event);
        return EmergencyResponse.from(repository.saveAndFlush(event));
    }

    /** Phase 11: how far back a phone may date an SOS it could not record at once (its own record only). */
    static final Duration MAX_OFFLINE_AGE = Duration.ofHours(24);

    private static final Pattern CLIENT_REF =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private static LocalDateTime toUtc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static LocalDateTime clamp(LocalDateTime value, LocalDateTime earliest, LocalDateTime latest) {
        if (value.isBefore(earliest)) return earliest;
        return value.isAfter(latest) ? latest : value;
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
        return end(userId, emergencyId, EmergencyStatus.CANCELLED, null, null);
    }

    /** "I'm safe": ACTIVE -> RESOLVED. */
    @Transactional
    public EmergencyResponse resolve(long userId, long emergencyId) {
        return end(userId, emergencyId, EmergencyStatus.RESOLVED, null, null);
    }

    /**
     * Phase 10A.4: ending an ACTIVE emergency. The sign-in that started it ends it as before (no password). Any OTHER
     * sign-in (another phone, or someone who signed in elsewhere with the password) must send the account password:
     * without one 403, a wrong one 409 (counted with the other password checks, 429 after 5 in 15 min). The record then
     * says it was ended elsewhere, so the first phone and the history can show it. Rule order: ownership (404), still
     * ACTIVE (409), then the password, so the password check says nothing about other people's emergencies.
     */
    @Transactional
    public EmergencyResponse end(long userId, long emergencyId, EmergencyStatus finalStatus, String sessionId,
                                 String password) {
        return end(userId, emergencyId, finalStatus, sessionId, password, null);
    }

    /** Phase 11: [endedAt] = when a phone that was offline ended it (clamped between the start and now). */
    @Transactional
    public EmergencyResponse end(long userId, long emergencyId, EmergencyStatus finalStatus, String sessionId,
                                 String password, Instant endedAt) {
        EmergencyEvent event = repository.findByIdAndUserIdForUpdate(emergencyId, userId)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND));
        if (event.getStatus() != EmergencyStatus.ACTIVE) {
            throw new ConflictException("This emergency has already ended");
        }
        boolean elsewhere = event.startedByAnotherSignIn(sessionId);
        if (elsewhere) {
            if (password == null || password.isEmpty()) {
                throw new ForbiddenException(ELSEWHERE);
            }
            User user = users.findById(userId).orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND));
            passwords.require(user, password);
        }
        if (endedAt == null) {
            event.end(finalStatus, elsewhere);
        } else {
            LocalDateTime now = LocalDateTime.ofInstant(Instant.now(clock), ZoneOffset.UTC);
            event.end(finalStatus, elsewhere, clamp(toUtc(endedAt), event.getStartedAt(), now));
        }
        return EmergencyResponse.from(repository.saveAndFlush(event));
    }

    static final String ELSEWHERE =
            "This SOS was started on another phone. Enter your account password to end it here.";

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

    private EmergencyEvent findOwned(long userId, long emergencyId) {
        return repository.findByIdAndUserId(emergencyId, userId)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND));
    }
}
