package com.safesphere.backend.social.service;

import com.safesphere.backend.common.exception.InvalidRequestException;
import com.safesphere.backend.common.exception.ResourceNotFoundException;
import com.safesphere.backend.social.dto.Relationship;
import com.safesphere.backend.social.entity.DailyState;
import com.safesphere.backend.social.repository.DailyStateRepository;
import com.safesphere.backend.social.repository.ProfileRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 17E (D-7): today's state. Chosen from a closed list; shown to the person and to their connections only (never on
 * a card, a list or to anyone else); counts only on the India calendar day it was chosen, so it ends exactly at midnight
 * IST (checked on every read, whatever the clean-up's timing); no history, no notification, no time anywhere.
 */
@Service
public class DailyStateService {

    /** The day boundary of D-7: midnight in India. */
    public static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    static final String NO_PROFILE = "Make your profile first";

    private final DailyStateRepository states;
    private final ProfileRepository profiles;
    private final SocialAccess access;
    private final Clock clock;

    public DailyStateService(DailyStateRepository states, ProfileRepository profiles, SocialAccess access, Clock clock) {
        this.states = states;
        this.profiles = profiles;
        this.access = access;
        this.clock = clock;
    }

    /** Sets (or replaces) the caller's state for today. A suspension stops it like any other profile change. */
    @Transactional
    public String set(long userId, String raw) {
        access.requireCanConnect(userId);
        if (profiles.findByUserId(userId).isEmpty()) throw new ResourceNotFoundException(NO_PROFILE);
        DailyState.Kind kind = parse(raw);
        LocalDate today = today();
        DailyState row = states.findById(userId).orElse(null);
        if (row == null) {
            row = new DailyState(userId, kind, today);
        } else {
            row.change(kind, today);
        }
        states.saveAndFlush(row);
        return kind.name();
    }

    /** Clears it (the same answer when there was none). */
    @Transactional
    public void clear(long userId) {
        access.requireMember(userId);
        states.findById(userId).ifPresent(states::delete);
    }

    /** The caller's own state for today, or null. */
    @Transactional(readOnly = true)
    public String own(long userId) {
        return current(userId);
    }

    /**
     * What [relationship] (the viewer's, already computed through the one visibility rule) may see of [ownerId]'s state:
     * only a connection (or the person) sees it; everyone else gets null, exactly as if there were none.
     */
    @Transactional(readOnly = true)
    public String visibleTo(Relationship relationship, long ownerId) {
        if (relationship != Relationship.CONNECTED && relationship != Relationship.SELF) return null;
        return current(ownerId);
    }

    /** Deletes every state of an earlier day (the clean-up job). */
    @Transactional
    public int purge() {
        return states.deleteBefore(today());
    }

    private String current(long userId) {
        LocalDate today = today();
        return states.findById(userId).filter(s -> s.getSetOn().equals(today)).map(s -> s.getState().name())
                .orElse(null);
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), INDIA);
    }

    private static DailyState.Kind parse(String raw) {
        try {
            return DailyState.Kind.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new InvalidRequestException("Choose a state from the list");
        }
    }
}
