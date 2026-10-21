package com.safesphere.backend.emergencycontext;

import com.safesphere.backend.circlealert.entity.CircleAlert;
import com.safesphere.backend.circlealert.repository.CircleAlertRepository;
import com.safesphere.backend.common.exception.InvalidRequestException;
import com.safesphere.backend.common.exception.ResourceNotFoundException;
import com.safesphere.backend.emergency.entity.EmergencyEvent;
import com.safesphere.backend.emergency.entity.EmergencyLocation;
import com.safesphere.backend.emergency.repository.EmergencyEventRepository;
import com.safesphere.backend.emergency.repository.EmergencyLocationRepository;
import com.safesphere.backend.emergencycontext.EmergencyContextDtos.AlertEntry;
import com.safesphere.backend.emergencycontext.EmergencyContextDtos.LatestLocation;
import com.safesphere.backend.emergencycontext.EmergencyContextDtos.OwnerContext;
import com.safesphere.backend.emergencycontext.EmergencyContextDtos.SafetyCircleContext;
import com.safesphere.backend.emergencycontext.EmergencyContextDtos.TimelineEntry;
import com.safesphere.backend.evidence.service.EvidenceService;
import com.safesphere.backend.escalation.entity.EscalationEvent;
import com.safesphere.backend.escalation.entity.EscalationLevel;
import com.safesphere.backend.escalation.repository.EscalationEventRepository;
import com.safesphere.backend.journey.entity.SafeJourney;
import com.safesphere.backend.journey.repository.SafeJourneyRepository;
import com.safesphere.backend.safetycontact.entity.SafetyContact;
import com.safesphere.backend.safetycontact.repository.SafetyContactRepository;
import com.safesphere.backend.user.entity.User;
import com.safesphere.backend.user.repository.UserRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Emergency Context Packet (approved Phase 4 design): a READ MODEL assembled on demand from the emergency, its
 * latest location, the Safe Walk that led to it, the escalation history and the circle alert log. There is no
 * "emergency_context" table: nothing is copied, so the packet can never be out of date or outlive its sources.
 *
 * Every audience gets an explicit allow-list projection. Only OWNER and SAFETY_CIRCLE exist today; the official
 * pathway and Verified Nearby Assistance are refused (no integration exists / reserved). Both available projections
 * are returned to the OWNER only (the Safety Circle one is a preview of what contacts are told): there is no public
 * or contact-facing endpoint in Phase 4.
 */
@Service
public class EmergencyContextService {

    private final EmergencyEventRepository emergencyRepository;
    private final EmergencyLocationRepository locationRepository;
    private final SafeJourneyRepository journeyRepository;
    private final EscalationEventRepository escalationRepository;
    private final CircleAlertRepository alertRepository;
    private final SafetyContactRepository contactRepository;
    private final UserRepository userRepository;
    private final EvidenceService evidenceService;
    private final Clock clock;

    public EmergencyContextService(EmergencyEventRepository emergencyRepository,
                                   EmergencyLocationRepository locationRepository,
                                   SafeJourneyRepository journeyRepository,
                                   EscalationEventRepository escalationRepository,
                                   CircleAlertRepository alertRepository,
                                   SafetyContactRepository contactRepository,
                                   UserRepository userRepository,
                                   EvidenceService evidenceService,
                                   Clock clock) {
        this.evidenceService = evidenceService;
        this.emergencyRepository = emergencyRepository;
        this.locationRepository = locationRepository;
        this.journeyRepository = journeyRepository;
        this.escalationRepository = escalationRepository;
        this.alertRepository = alertRepository;
        this.contactRepository = contactRepository;
        this.userRepository = userRepository;
        this.clock = clock;
    }

    /** Parses the audience text so unknown and unavailable audiences get clear 400 answers. */
    public static ContextAudience parseAudience(String value) {
        if (value == null || value.isBlank()) return ContextAudience.OWNER;
        ContextAudience audience;
        try {
            audience = ContextAudience.valueOf(value.strip().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("Unknown audience");
        }
        if (!audience.isAvailable()) {
            throw new InvalidRequestException("This audience is not available");
        }
        return audience;
    }

    @Transactional(readOnly = true)
    public Object packet(long userId, long emergencyId, ContextAudience audience) {
        EmergencyEvent emergency = emergencyRepository.findByIdAndUserId(emergencyId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Emergency not found"));
        EmergencyLocation latest = locationRepository.findFirstByEmergencyIdOrderByRecordedAtDescIdDesc(emergencyId)
                .orElse(null);
        return switch (audience) {
            case OWNER -> owner(userId, emergency, latest);
            case SAFETY_CIRCLE -> safetyCircle(userId, emergency, latest);
            default -> throw new InvalidRequestException("This audience is not available");
        };
    }

    private OwnerContext owner(long userId, EmergencyEvent emergency, EmergencyLocation latest) {
        SafeJourney journey = emergency.getJourneyId() == null ? null
                : journeyRepository.findByIdAndUserId(emergency.getJourneyId(), userId).orElse(null);

        // Escalations of the emergency and of the walk that led to it (the SOS row belongs to both: kept once).
        Map<Long, TimelineEntry> timeline = new LinkedHashMap<>();
        List<EscalationEvent> events = new ArrayList<>();
        if (journey != null) events.addAll(escalationRepository.findByJourneyIdOrderByCreatedAtAscIdAsc(journey.getId()));
        events.addAll(escalationRepository.findByEmergencyIdOrderByCreatedAtAscIdAsc(emergency.getId()));
        events.stream()
                .sorted(Comparator.comparing(EscalationEvent::getCreatedAt).thenComparing(EscalationEvent::getId))
                .forEach(e -> timeline.putIfAbsent(e.getId(), new TimelineEntry(
                        e.getLevel().code(), e.getReason().name(), utc(e.getCreatedAt()),
                        e.getEmergencyId() != null ? "EMERGENCY" : "SAFE_WALK")));
        // A plain SOS has no level-3 row of its own: the ACTIVE emergency IS level 3.
        int highest = Math.max(EscalationLevel.EMERGENCY.code(),
                timeline.values().stream().mapToInt(TimelineEntry::level).max().orElse(0));

        Map<Long, String> contactNames = contactRepository.findByUserIdOrderByPrimaryContactDescPriorityAscNameAsc(userId)
                .stream().collect(Collectors.toMap(SafetyContact::getId, SafetyContact::getName));
        List<AlertEntry> alerts = new ArrayList<>();
        if (journey != null) {
            alertRepository.findByJourneyIdOrderByOccurredAtAscIdAsc(journey.getId())
                    .forEach(a -> alerts.add(alertEntry(a, contactNames, "SAFE_WALK")));
        }
        alertRepository.findByEmergencyIdOrderByOccurredAtAscIdAsc(emergency.getId())
                .forEach(a -> alerts.add(alertEntry(a, contactNames, "EMERGENCY")));
        alerts.sort(Comparator.comparing(AlertEntry::occurredAt));

        return new OwnerContext(
                ContextAudience.OWNER.name(),
                Instant.now(clock),
                new EmergencyContextDtos.Emergency(emergency.getId(), emergency.getStatus().name(),
                        emergency.getTriggerType().name(), utc(emergency.getStartedAt()), utc(emergency.getEndedAt())),
                journey == null ? null : new EmergencyContextDtos.Journey(journey.getId(), journey.getStatus().name(),
                        journey.getDestinationLabel(), utc(journey.getStartedAt()), utc(journey.getEndedAt())),
                latest == null ? null : new LatestLocation(latest.getLatitude(), latest.getLongitude(),
                        latest.getAccuracyMeters(), latest.getBatteryPercent(), utc(latest.getRecordedAt()),
                        mapLink(latest.getLatitude(), latest.getLongitude())),
                locationRepository.countByEmergencyId(emergency.getId()),
                highest,
                List.copyOf(timeline.values()),
                alerts,
                evidenceSummary(emergency.getId())
        );
    }

    private EmergencyContextDtos.EvidenceSummary evidenceSummary(long emergencyId) {
        EvidenceService.Summary summary = evidenceService.summary(emergencyId);
        return new EmergencyContextDtos.EvidenceSummary(summary.items(), summary.sealed(), summary.uploading(),
                summary.deleted());
    }

    /** The allow-list: name, type, status, times, location link. Nothing else is read into it. */
    private SafetyCircleContext safetyCircle(long userId, EmergencyEvent emergency, EmergencyLocation latest) {
        String name = userRepository.findById(userId).map(User::getName).orElse("SafeSphere user");
        return new SafetyCircleContext(
                ContextAudience.SAFETY_CIRCLE.name(),
                name,
                "SOS",
                emergency.getStatus().name(),
                utc(emergency.getStartedAt()),
                utc(emergency.getEndedAt()),
                latest == null ? null : mapLink(latest.getLatitude(), latest.getLongitude()),
                latest == null ? null : utc(latest.getRecordedAt())
        );
    }

    private static AlertEntry alertEntry(CircleAlert alert, Map<Long, String> contactNames, String source) {
        String name = alert.getContactId() == null ? null : contactNames.get(alert.getContactId());
        return new AlertEntry(alert.getKind().name(), alert.getChannel().name(), alert.getStatus().name(), name,
                utc(alert.getOccurredAt()), source);
    }

    /** A standard maps link (Google Maps URLs API); opening it needs no app or account. Same format as the phone's. */
    static String mapLink(BigDecimal latitude, BigDecimal longitude) {
        return "https://www.google.com/maps/search/?api=1&query="
                + latitude.stripTrailingZeros().toPlainString() + "," + longitude.stripTrailingZeros().toPlainString();
    }

    private static Instant utc(LocalDateTime time) {
        return time == null ? null : time.toInstant(ZoneOffset.UTC);
    }
}
