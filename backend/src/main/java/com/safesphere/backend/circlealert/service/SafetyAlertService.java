package com.safesphere.backend.circlealert.service;

import com.safesphere.backend.auth.phone.PhoneNumbers;
import com.safesphere.backend.circlealert.dto.AlertDtos.AlertState;
import com.safesphere.backend.circlealert.dto.AlertDtos.ContactPlan;
import com.safesphere.backend.circlealert.dto.AlertDtos.EmergencyAlerts;
import com.safesphere.backend.circlealert.dto.AlertDtos.Settings;
import com.safesphere.backend.circlealert.dto.AlertDtos.StopState;
import com.safesphere.backend.circlealert.dto.AlertDtos.Test;
import com.safesphere.backend.circlealert.entity.AlertSetting;
import com.safesphere.backend.circlealert.entity.AlertStop;
import com.safesphere.backend.circlealert.entity.CircleAlert;
import com.safesphere.backend.circlealert.entity.CircleAlertEnums.Channel;
import com.safesphere.backend.circlealert.entity.CircleAlertEnums.Kind;
import com.safesphere.backend.circlealert.entity.CircleAlertEnums.SkipReason;
import com.safesphere.backend.circlealert.entity.CircleAlertEnums.Status;
import com.safesphere.backend.circlealert.repository.AlertSettingRepository;
import com.safesphere.backend.circlealert.repository.AlertStopRepository;
import com.safesphere.backend.circlealert.repository.CircleAlertRepository;
import com.safesphere.backend.common.exception.ConflictException;
import com.safesphere.backend.common.exception.InvalidRequestException;
import com.safesphere.backend.common.exception.ResourceNotFoundException;
import com.safesphere.backend.common.exception.TooManyAttemptsException;
import com.safesphere.backend.config.AlertProperties;
import com.safesphere.backend.emergency.entity.EmergencyEvent;
import com.safesphere.backend.emergency.repository.EmergencyEventRepository;
import com.safesphere.backend.safetycontact.entity.SafetyContact;
import com.safesphere.backend.user.entity.User;
import com.safesphere.backend.user.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * N6 (D20): what the person sees and chooses about the automatic Safety Circle texts, and the admin stop list.
 * Everything is the caller's own (identity from the JWT; another person's emergency answers 404).
 */
@Service
public class SafetyAlertService {

    static final String TEST_LIMIT = "You can test your alerts once a day. Try again tomorrow.";

    private final AlertSettingRepository settings;
    private final CircleAlertRepository alerts;
    private final AlertStopRepository stops;
    private final EmergencyEventRepository emergencies;
    private final UserRepository users;
    private final AlertQueue queue;
    private final AlertKeys keys;
    private final AlertDispatcher dispatcher;
    private final AlertProperties properties;
    private final Clock clock;

    public SafetyAlertService(AlertSettingRepository settings, CircleAlertRepository alerts, AlertStopRepository stops,
                              EmergencyEventRepository emergencies, UserRepository users, AlertQueue queue,
                              AlertKeys keys, AlertDispatcher dispatcher, AlertProperties properties, Clock clock) {
        this.settings = settings;
        this.alerts = alerts;
        this.stops = stops;
        this.emergencies = emergencies;
        this.users = users;
        this.queue = queue;
        this.keys = keys;
        this.dispatcher = dispatcher;
        this.properties = properties;
        this.clock = clock;
    }

    // ---- the person's choice ---------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Settings settings(long userId) {
        User user = users.findById(userId).orElseThrow(() -> new ResourceNotFoundException("Account not found"));
        AlertSetting setting = settings.findById(userId).orElse(null);
        String choice = setting == null ? "UNSET" : setting.isServerSms() ? "ON" : "OFF";
        String preview = user.getPhoneE164() == null ? null
                : AlertPolicy.preview(user.getName(), user.getPhoneE164(), Instant.now(clock));
        Map<Long, SafetyContact> byId = queue.enabledContacts(userId).stream()
                .collect(Collectors.toMap(SafetyContact::getId, c -> c));
        List<ContactPlan> contacts = AlertPolicy.plan(queue.recipients(queue.enabledContacts(userId)),
                        stops::existsByNumberKey, properties.maxContacts()).stream()
                .map(p -> new ContactPlan(p.contactId(), byId.get(p.contactId()).getName(), p.texted(),
                        p.texted() ? null : shown(p.reason())))
                .toList();
        Test lastTest = alerts.findFirstByUserIdAndKindOrderByIdDesc(userId, Kind.TEST).map(SafetyAlertService::test)
                .orElse(null);
        return new Settings(choice, setting == null ? null : utc(setting.getDecidedAt()), preview, contacts, lastTest,
                user.getPhoneE164() != null && testsToday(userId) < properties.testsPerPerson());
    }

    /** The one explicit choice (and every later change). */
    @Transactional
    public Settings choose(long userId, boolean on) {
        LocalDateTime now = now();
        AlertSetting setting = settings.findById(userId).orElse(null);
        if (setting == null) {
            try {
                settings.saveAndFlush(new AlertSetting(userId, on, now));
            } catch (DataIntegrityViolationException e) {
                throw new ConflictException("Your choice was changed at the same time. Please try again.");
            }
        } else {
            setting.change(on, now);
            settings.save(setting);
        }
        return settings(userId);
    }

    /** "Test my alerts": the Circle text's chain, sent to the person's OWN number. Once per 24 h. */
    @Transactional
    public Settings test(long userId) {
        User user = users.findById(userId).orElseThrow(() -> new ResourceNotFoundException("Account not found"));
        if (user.getPhoneE164() == null) throw new ConflictException("Add your phone number first");
        if (testsToday(userId) >= properties.testsPerPerson()) throw new TooManyAttemptsException(TEST_LIMIT);
        alerts.save(CircleAlert.test(userId, now()));
        kickAfterCommit();
        return settings(userId);
    }

    // ---- what happened in an SOS -----------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public EmergencyAlerts forEmergency(long userId, long emergencyId) {
        EmergencyEvent sos = emergencies.findByIdAndUserId(emergencyId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Emergency not found"));
        Map<Long, String> names = queue.contactNames(userId);
        boolean late = AlertPolicy.late(sos.getStartedAt(), sos.getCreatedAt(), properties.lateAfter());
        List<AlertState> states = alerts.findByEmergencyIdAndChannelOrderByIdAsc(emergencyId, Channel.SERVER_SMS)
                .stream()
                .map(a -> new AlertState(a.getContactId(), a.getContactId() == null ? null : names.get(a.getContactId()),
                        a.getKind().name(), a.getStatus().name(), a.getSkipReason() == null ? null : shown(a.getSkipReason()),
                        utc(a.getSentAt() != null ? a.getSentAt() : a.getUpdatedAt()),
                        late && a.getKind() == Kind.SOS_STARTED && a.getStatus() != Status.SKIPPED))
                .toList();
        String choice = settings.findById(userId).map(s -> s.isServerSms() ? "ON" : "OFF").orElse("UNSET");
        return new EmergencyAlerts(choice, states);
    }

    // ---- admin stop list -------------------------------------------------------------------------------------------

    @Transactional
    public void stop(long adminId, String rawNumber) {
        String key = keys.of(number(rawNumber));
        if (stops.existsByNumberKey(key)) return;
        try {
            stops.saveAndFlush(new AlertStop(key, adminId, now()));
        } catch (DataIntegrityViolationException e) {
            // added at the same time by another request: the number is on the list either way
        }
    }

    @Transactional
    public void lift(String rawNumber) {
        stops.findByNumberKey(keys.of(number(rawNumber))).ifPresent(stops::delete);
    }

    @Transactional(readOnly = true)
    public StopState check(String rawNumber) {
        return new StopState(stops.existsByNumberKey(keys.of(number(rawNumber))));
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private long testsToday(long userId) {
        return alerts.countByUserIdAndKindAndOccurredAtAfter(userId, Kind.TEST, now().minusHours(24));
    }

    private void kickAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatcher.kick();
                }
            });
        } else {
            dispatcher.kick();
        }
    }

    private static String number(String raw) {
        String number = PhoneNumbers.normalize(raw);
        if (number == null) throw new InvalidRequestException("Enter an Indian mobile number");
        return number;
    }

    /** The person never learns that a contact asked IRL for no alerts. */
    static String shown(SkipReason reason) {
        return reason == SkipReason.STOPPED ? "NOT_AVAILABLE" : reason.name();
    }

    private static Test test(CircleAlert a) {
        return new Test(a.getStatus().name(), utc(a.getSentAt() != null ? a.getSentAt() : a.getUpdatedAt()),
                a.getSkipReason() == null ? null : shown(a.getSkipReason()));
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(Instant.now(clock), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    private static Instant utc(LocalDateTime time) {
        return time == null ? null : time.toInstant(ZoneOffset.UTC);
    }
}
