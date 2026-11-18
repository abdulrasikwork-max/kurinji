package com.safesphere.backend.circlealert.service;

import com.safesphere.backend.circlealert.entity.CircleAlert;
import com.safesphere.backend.circlealert.entity.CircleAlertEnums.Channel;
import com.safesphere.backend.circlealert.entity.CircleAlertEnums.Kind;
import com.safesphere.backend.circlealert.entity.CircleAlertEnums.Status;
import com.safesphere.backend.circlealert.repository.CircleAlertRepository;
import com.safesphere.backend.common.exception.ResourceNotFoundException;
import com.safesphere.backend.config.AlertProperties;
import com.safesphere.backend.emergency.entity.EmergencyEvent;
import com.safesphere.backend.emergency.entity.EmergencyLocation;
import com.safesphere.backend.emergency.entity.EmergencyStatus;
import com.safesphere.backend.emergency.repository.EmergencyEventRepository;
import com.safesphere.backend.emergency.repository.EmergencyLocationRepository;
import com.safesphere.backend.safetycontact.service.CircleLinkService;
import com.safesphere.backend.user.entity.User;
import com.safesphere.backend.user.repository.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * N7 (D36 = N-128): what an accepted Safety Circle member sees of someone's SOS in the app: exactly the facts of the text
 * (name, their number to call, when it started, the map link the texts carried, "sent late") plus where it stands.
 * Only for a member who was actually texted about THIS SOS, while it runs and for 24 h after; anyone else: not found.
 * No history, no live tracking: the location is the one the texts carried, never newer.
 */
@Service
public class CircleSosViewService {

    static final Duration AFTER_THE_END = Duration.ofHours(24);
    private static final Set<Status> SENT = Set.of(Status.SENT, Status.DELIVERED);

    public enum Standing { ACTIVE, SAFE, FALSE_ALARM, ENDED }

    public record CircleSos(String name, String phone, Instant startedAt, Standing status, String mapLink,
                            Instant locationAt, boolean sentLate) {
    }

    private final EmergencyEventRepository emergencies;
    private final EmergencyLocationRepository locations;
    private final CircleAlertRepository alerts;
    private final CircleLinkService links;
    private final AlertQueue queue;
    private final UserRepository users;
    private final AlertProperties properties;
    private final Clock clock;

    public CircleSosViewService(EmergencyEventRepository emergencies, EmergencyLocationRepository locations,
                                CircleAlertRepository alerts, CircleLinkService links, UserRepository users,
                                AlertProperties properties, Clock clock, AlertQueue queue) {
        this.queue = queue;
        this.emergencies = emergencies;
        this.locations = locations;
        this.alerts = alerts;
        this.links = links;
        this.users = users;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public CircleSos view(long memberId, long emergencyId) {
        EmergencyEvent sos = emergencies.findById(emergencyId).orElseThrow(CircleSosViewService::notFound);
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        if (sos.getStatus() != EmergencyStatus.ACTIVE
                && (sos.getEndedAt() == null || sos.getEndedAt().isBefore(now.minus(AFTER_THE_END)))) {
            throw notFound();
        }
        long ownerId = sos.getUserId();
        if (ownerId == memberId || !links.isAcceptedMember(memberId, ownerId)) throw notFound();
        if (!queue.membersTold(emergencyId).contains(memberId)) throw notFound();
        List<CircleAlert> rows = alerts.findByEmergencyIdAndChannelOrderByIdAsc(emergencyId, Channel.SERVER_SMS);
        User owner = users.findById(ownerId).filter(u -> !u.isErased()).orElseThrow(CircleSosViewService::notFound);

        LocalDateTime lastWithPlace = rows.stream()
                .filter(r -> (r.getKind() == Kind.SOS_STARTED || r.getKind() == Kind.SOS_LOCATION) && r.isIncludedLocation()
                        && SENT.contains(r.getStatus()) && r.getSentAt() != null)
                .map(CircleAlert::getSentAt).max(Comparator.naturalOrder()).orElse(null);
        EmergencyLocation place = lastWithPlace == null ? null
                : locations.findTop100ByEmergencyIdOrderByRecordedAtDescIdDesc(emergencyId).stream()
                        .filter(l -> !l.getRecordedAt().isAfter(lastWithPlace)).findFirst().orElse(null);
        String link = place == null ? null : "https://" + AlertPolicy.mapLink(
                new AlertPolicy.Position(place.getLatitude(), place.getLongitude()));
        return new CircleSos(owner.getName(), owner.getPhoneE164(), sos.getStartedAt().toInstant(ZoneOffset.UTC),
                standing(sos), link, place == null ? null : place.getRecordedAt().toInstant(ZoneOffset.UTC),
                AlertPolicy.late(sos.getStartedAt(), sos.getCreatedAt(), properties.lateAfter()));
    }

    /** Only the person on the phone can say they are safe (N2 / N6): an SOS ended elsewhere is just "ended". */
    static Standing standing(EmergencyEvent sos) {
        if (sos.getStatus() == EmergencyStatus.ACTIVE) return Standing.ACTIVE;
        if (sos.isEndedElsewhere()) return Standing.ENDED;
        return sos.getStatus() == EmergencyStatus.RESOLVED ? Standing.SAFE : Standing.FALSE_ALARM;
    }

    private static ResourceNotFoundException notFound() {
        return new ResourceNotFoundException("Alert not found");
    }
}
