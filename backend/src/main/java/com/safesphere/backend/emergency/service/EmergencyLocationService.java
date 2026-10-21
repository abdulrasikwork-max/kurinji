package com.safesphere.backend.emergency.service;

import com.safesphere.backend.common.exception.ConflictException;
import com.safesphere.backend.common.exception.InvalidRequestException;
import com.safesphere.backend.common.exception.ResourceNotFoundException;
import com.safesphere.backend.config.EmergencyLocationProperties;
import com.safesphere.backend.emergency.dto.LocationPointRequest;
import com.safesphere.backend.emergency.dto.LocationResponse;
import com.safesphere.backend.emergency.dto.LocationUploadResponse;
import com.safesphere.backend.emergency.entity.EmergencyEvent;
import com.safesphere.backend.emergency.entity.EmergencyLocation;
import com.safesphere.backend.emergency.entity.EmergencyStatus;
import com.safesphere.backend.emergency.entity.LocationSource;
import com.safesphere.backend.emergency.repository.EmergencyEventRepository;
import com.safesphere.backend.emergency.repository.EmergencyLocationRepository;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores and returns the location points of the caller's own emergencies.
 *
 * Rules (all checked against the SERVER clock, never the phone's):
 *  - the emergency must belong to the caller, otherwise 404 (same as "does not exist");
 *  - points are accepted while the emergency is ACTIVE and for a short grace period after it ended,
 *    so a phone that was offline can still upload what it recorded;
 *  - each point's time must lie inside the emergency (with a small tolerance for phone clock drift);
 *  - a batch is all-or-nothing: one invalid point rejects the whole request;
 *  - points the server already has (same emergency and time) are skipped, so re-sending is safe;
 *  - one emergency stores at most a configurable number of points.
 *
 * Nothing here sends the location to anyone: it is only stored for the owner. Coordinates are never logged.
 */
@Service
public class EmergencyLocationService {

    private static final String EMERGENCY_NOT_FOUND = "Emergency not found";
    private static final String NO_LOCATION = "No location recorded for this emergency";

    private final EmergencyEventRepository emergencyRepository;
    private final EmergencyLocationRepository locationRepository;
    private final EmergencyLocationProperties properties;
    private final Clock clock;

    public EmergencyLocationService(EmergencyEventRepository emergencyRepository,
                                    EmergencyLocationRepository locationRepository,
                                    EmergencyLocationProperties properties,
                                    Clock clock) {
        this.emergencyRepository = emergencyRepository;
        this.locationRepository = locationRepository;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public LocationUploadResponse upload(long userId, long emergencyId, List<LocationPointRequest> points) {
        // Locks the emergency row: concurrent uploads for the same emergency run one after the other.
        EmergencyEvent emergency = emergencyRepository.findByIdAndUserIdForUpdate(emergencyId, userId)
                .orElseThrow(() -> new ResourceNotFoundException(EMERGENCY_NOT_FOUND));
        LocalDateTime now = LocalDateTime.ofInstant(Instant.now(clock), ZoneOffset.UTC);

        if (emergency.getStatus() != EmergencyStatus.ACTIVE
                && emergency.getEndedAt().plus(properties.uploadGrace()).isBefore(now)) {
            throw new ConflictException("This emergency is no longer accepting locations");
        }

        // Validate EVERY point before writing anything (all-or-nothing). Duplicates inside the batch collapse.
        Map<LocalDateTime, LocationPointRequest> byTime = new LinkedHashMap<>();
        for (LocationPointRequest point : points) {
            LocalDateTime recordedAt = toUtcMicros(point.recordedAt());
            checkRecordedAt(recordedAt, emergency, now);
            byTime.putIfAbsent(recordedAt, point);
        }

        Set<LocalDateTime> alreadyStored =
                new HashSet<>(locationRepository.findExistingRecordedAt(emergencyId, byTime.keySet()));
        List<EmergencyLocation> toStore = new ArrayList<>();
        byTime.forEach((recordedAt, point) -> {
            if (!alreadyStored.contains(recordedAt)) {
                toStore.add(toEntity(emergencyId, point, recordedAt, now));
            }
        });

        if (locationRepository.countByEmergencyId(emergencyId) + toStore.size() > properties.maxPerEmergency()) {
            throw new ConflictException("Location limit reached for this emergency");
        }
        locationRepository.saveAll(toStore);
        return new LocationUploadResponse(points.size(), toStore.size());
    }

    @Transactional(readOnly = true)
    public LocationResponse latest(long userId, long emergencyId) {
        findOwned(userId, emergencyId);
        return locationRepository.findFirstByEmergencyIdOrderByRecordedAtDescIdDesc(emergencyId)
                .map(LocationResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException(NO_LOCATION));
    }

    @Transactional(readOnly = true)
    public List<LocationResponse> recent(long userId, long emergencyId) {
        findOwned(userId, emergencyId);
        return locationRepository.findTop100ByEmergencyIdOrderByRecordedAtDescIdDesc(emergencyId).stream()
                .map(LocationResponse::from)
                .toList();
    }

    private void checkRecordedAt(LocalDateTime recordedAt, EmergencyEvent emergency, LocalDateTime now) {
        if (recordedAt.isAfter(now.plus(properties.clockTolerance()))) {
            throw new InvalidRequestException("Location time cannot be in the future");
        }
        if (recordedAt.isBefore(emergency.getStartedAt().minus(properties.clockTolerance()))) {
            throw new InvalidRequestException("Location time is before the emergency started");
        }
        if (emergency.getEndedAt() != null
                && recordedAt.isAfter(emergency.getEndedAt().plus(properties.clockTolerance()))) {
            throw new InvalidRequestException("Location time is after the emergency ended");
        }
    }

    private static EmergencyLocation toEntity(long emergencyId, LocationPointRequest point,
                                              LocalDateTime recordedAt, LocalDateTime now) {
        return new EmergencyLocation(
                emergencyId,
                point.latitude().setScale(6, RoundingMode.HALF_UP),
                point.longitude().setScale(6, RoundingMode.HALF_UP),
                point.accuracyMeters() == null ? null : point.accuracyMeters().setScale(1, RoundingMode.HALF_UP),
                point.source() == null ? LocationSource.UNKNOWN : point.source(),
                point.batteryPercent(),
                recordedAt,
                now
        );
    }

    /** The database keeps microseconds (DATETIME(6)); truncating first makes duplicate detection exact. */
    private static LocalDateTime toUtcMicros(Instant instant) {
        return LocalDateTime.ofInstant(instant.truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
    }

    private void findOwned(long userId, long emergencyId) {
        emergencyRepository.findByIdAndUserId(emergencyId, userId)
                .orElseThrow(() -> new ResourceNotFoundException(EMERGENCY_NOT_FOUND));
    }
}
