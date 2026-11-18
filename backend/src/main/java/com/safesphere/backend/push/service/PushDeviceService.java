package com.safesphere.backend.push.service;

import com.safesphere.backend.auth.entity.DeviceSession;
import com.safesphere.backend.auth.repository.DeviceSessionRepository;
import com.safesphere.backend.common.exception.ConflictException;
import com.safesphere.backend.common.exception.InvalidRequestException;
import com.safesphere.backend.common.exception.TooManyAttemptsException;
import com.safesphere.backend.push.dto.PushDtos.Settings;
import com.safesphere.backend.push.entity.PushDevice;
import com.safesphere.backend.push.entity.PushSetting;
import com.safesphere.backend.push.repository.PushDeviceRepository;
import com.safesphere.backend.push.repository.PushMessageRepository;
import com.safesphere.backend.push.repository.PushSettingRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * N7 (D28 = N-120, D31 = N-123): a phone's token belongs to ONE sign-in. The caller and the sign-in come only from the
 * token (`sub`, `sid`): a phone can only register itself. A token seen on another sign-in moves here (the old row goes),
 * so a phone handed from one person to another never gets the first person's pushes. Settings are the caller's own.
 */
@Service
public class PushDeviceService {

    /** Registrations per sign-in per hour (a phone refreshes its token rarely; more is a loop or abuse). */
    static final int REGISTRATIONS_PER_HOUR = 10;

    private final PushDeviceRepository devices;
    private final PushMessageRepository messages;
    private final PushSettingRepository settings;
    private final DeviceSessionRepository sessions;
    private final Clock clock;
    private final Map<String, Deque<Instant>> recent = new ConcurrentHashMap<>();

    public PushDeviceService(PushDeviceRepository devices, PushMessageRepository messages, PushSettingRepository settings,
                             DeviceSessionRepository sessions, Clock clock) {
        this.devices = devices;
        this.messages = messages;
        this.settings = settings;
        this.sessions = sessions;
        this.clock = clock;
    }

    @Transactional
    public void register(long userId, String sessionId, String token, String appVersion) {
        if (token == null || !PushPolicy.TOKEN.matcher(token).matches()) {
            throw new InvalidRequestException("That is not a notification token");
        }
        DeviceSession session = activeSession(userId, sessionId);
        limit(sessionId);
        LocalDateTime now = now();
        String hash = sha256(token);
        devices.findByTokenHash(hash).ifPresent(other -> {
            if (!other.getSessionId().equals(session.getId())) {
                messages.deleteByDeviceIds(List.of(other.getId()));
                devices.delete(other);
                devices.flush();
            }
        });
        String version = appVersion == null || appVersion.isBlank() ? null : appVersion.trim();
        PushDevice mine = devices.findBySessionId(session.getId()).orElse(null);
        if (mine == null) {
            devices.save(new PushDevice(userId, session.getId(), token, hash, version, now));
        } else {
            mine.refresh(token, hash, version, now);
        }
    }

    /** This phone no longer wants pushes (notifications turned off, signing out). Nothing to remove is fine. */
    @Transactional
    public void unregister(long userId, String sessionId) {
        if (sessionId == null) return;
        sessions.findBySessionId(sessionId)
                .filter(s -> s.getUserId() == userId)
                .flatMap(s -> devices.findBySessionId(s.getId()))
                .ifPresent(device -> {
                    messages.deleteByDeviceIds(List.of(device.getId()));
                    devices.delete(device);
                });
    }

    @Transactional(readOnly = true)
    public Settings settings(long userId) {
        PushSetting setting = settings.findById(userId).orElseGet(() -> new PushSetting(userId, now()));
        return view(setting);
    }

    @Transactional
    public Settings change(long userId, Settings wanted) {
        LocalDateTime now = now();
        PushSetting setting = settings.findById(userId).orElseGet(() -> new PushSetting(userId, now));
        setting.change(wanted.replies(), wanted.plans(), wanted.planMessages(), wanted.people(), wanted.staff(), now);
        settings.save(setting);
        return view(setting);
    }

    private DeviceSession activeSession(long userId, String sessionId) {
        if (sessionId == null) throw new ConflictException("Sign in again to get notifications on this phone");
        DeviceSession session = sessions.findBySessionId(sessionId).orElse(null);
        if (session == null || session.getUserId() != userId || session.isRevoked() || session.isExpired(now())) {
            throw new ConflictException("Sign in again to get notifications on this phone");
        }
        return session;
    }

    private void limit(String sessionId) {
        Instant now = clock.instant();
        Deque<Instant> times = recent.computeIfAbsent(sessionId, k -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && times.peekFirst().isBefore(now.minus(Duration.ofHours(1)))) times.pollFirst();
            if (times.size() >= REGISTRATIONS_PER_HOUR) {
                throw new TooManyAttemptsException("Too many notification sign-ups from this phone. Try again later.");
            }
            times.addLast(now);
        }
    }

    private static Settings view(PushSetting setting) {
        return new Settings(setting.isReplies(), setting.isPlans(), setting.isPlanMessages(), setting.isPeople(),
                setting.isStaff());
    }

    static String sha256(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
}
