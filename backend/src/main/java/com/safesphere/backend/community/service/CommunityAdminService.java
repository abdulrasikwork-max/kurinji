package com.safesphere.backend.community.service;

import com.safesphere.backend.auth.phone.PhoneNumbers;
import com.safesphere.backend.common.exception.ConflictException;
import com.safesphere.backend.common.exception.InvalidRequestException;
import com.safesphere.backend.common.exception.ResourceNotFoundException;
import com.safesphere.backend.community.dto.CommunityDtos.LogEntry;
import com.safesphere.backend.community.dto.CommunityDtos.NameChange;
import com.safesphere.backend.community.dto.CommunityDtos.VerifierChange;
import com.safesphere.backend.community.dto.CommunityDtos.VerifierView;
import com.safesphere.backend.community.entity.CommunityAction;
import com.safesphere.backend.community.entity.CommunityEnums.ActionKind;
import com.safesphere.backend.community.entity.Membership;
import com.safesphere.backend.community.repository.CommunityActionRepository;
import com.safesphere.backend.community.repository.MembershipRepository;
import com.safesphere.backend.user.entity.User;
import com.safesphere.backend.user.repository.UserRepository;
import com.safesphere.backend.user.service.RealNamePolicy;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase N3: what only an IRL admin does in the community besides deciding ID photos: appoint and remove verifiers,
 * correct a checked name, read the log. People are named by their verified phone number (as role changes since N2), so
 * no id of another person is ever needed by the app. Every change is logged with the admin as actor.
 */
@Service
public class CommunityAdminService {

    private static final int PAGE = 50;

    private final UserRepository users;
    private final MembershipRepository memberships;
    private final CommunityActionRepository actions;
    private final MembershipLookup lookup;
    private final DecisionService decisions;
    private final Clock clock;

    public CommunityAdminService(UserRepository users, MembershipRepository memberships,
                                 CommunityActionRepository actions, MembershipLookup lookup, DecisionService decisions,
                                 Clock clock) {
        this.users = users;
        this.memberships = memberships;
        this.actions = actions;
        this.lookup = lookup;
        this.decisions = decisions;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<VerifierView> verifiers(long adminId) {
        decisions.requireAdmin(adminId);
        return memberships.verifiersOf(lookup.miet().getId()).stream().map(m -> {
            User u = users.findById(m.getUserId()).orElseThrow();
            return new VerifierView(u.getName(), PhoneNumbers.hint(u.getPhoneE164()), m.getProgramme().name(),
                    m.getGraduationYear(), m.getVerifierSince().toInstant(ZoneOffset.UTC));
        }).toList();
    }

    /** Appoints or removes a verifier: the person must be an active member (a verifier is a member's role). */
    @Transactional
    public void changeVerifier(long adminId, VerifierChange change) {
        decisions.requireAdmin(adminId);
        if (change == null || change.verifier() == null) throw new InvalidRequestException("Say appoint or remove");
        User target = byPhone(change.phone());
        LocalDateTime now = now();
        Membership m = memberships.findByUserIdForUpdate(target.getId()).filter(x -> x.isActive(now))
                .orElseThrow(() -> new ConflictException("Only a verified member can be a verifier"));
        if (m.isVerifier() == change.verifier()) return; // already so: nothing to log
        m.makeVerifier(change.verifier(), now);
        memberships.saveAndFlush(m);
        actions.save(new CommunityAction(m.getCommunityId(), adminId, target.getId(),
                change.verifier() ? ActionKind.VERIFIER_ADDED : ActionKind.VERIFIER_REMOVED, null, null, null, now));
    }

    /** An admin corrects a checked name (N-24: the person cannot); logged with the earlier spelling, shown to them. */
    @Transactional
    public void correctName(long adminId, NameChange change) {
        decisions.requireAdmin(adminId);
        if (change == null) throw new InvalidRequestException("Give the number and the name");
        String name = RealNamePolicy.clean(change.name());
        RealNamePolicy.Result check = RealNamePolicy.check(name);
        if (check != RealNamePolicy.Result.OK) throw new InvalidRequestException(RealNamePolicy.message(check));
        User target = users.findByIdForUpdate(byPhone(change.phone()).getId()).orElseThrow();
        if (name.equals(target.getName())) return;
        LocalDateTime now = now();
        actions.save(new CommunityAction(lookup.miet().getId(), adminId, target.getId(), ActionKind.NAME_CORRECTED,
                null, null, target.getName(), now));
        target.rename(name);
        target.markNameChecked(now);
        users.saveAndFlush(target);
    }

    /** The newest log entries first, [page] of 50. Names are shown as they are now (a deleted person is gone). */
    @Transactional(readOnly = true)
    public List<LogEntry> log(long adminId, int page) {
        decisions.requireAdmin(adminId);
        List<CommunityAction> rows = actions.newestFirst(lookup.miet().getId(),
                PageRequest.of(Math.max(0, page), PAGE));
        Map<Long, String> names = new HashMap<>();
        users.findAllById(rows.stream().flatMap(a -> Stream.of(a.getActorId(), a.getSubjectUserId()))
                .filter(Objects::nonNull).distinct().toList()).forEach(u -> names.put(u.getId(), u.getName()));
        return rows.stream().map(a -> new LogEntry(a.getAction().name(),
                a.getMethod() == null ? null : a.getMethod().name(), a.getReason(),
                a.getActorId() == null ? "IRL" : names.getOrDefault(a.getActorId(), "Deleted account"),
                a.getSubjectUserId() == null ? null : names.get(a.getSubjectUserId()), a.getDetail(),
                a.getCreatedAt().toInstant(ZoneOffset.UTC))).toList();
    }

    /** A verified account by its number (any common way of typing it); 404 the same for unknown and unverified. */
    User byPhone(String raw) {
        String phone = PhoneNumbers.normalize(raw);
        return (phone == null ? java.util.Optional.<User>empty() : users.findByPhoneE164AndPhoneVerifiedAtIsNotNull(phone))
                .filter(u -> u.isEnabled() && !u.isErased())
                .orElseThrow(() -> new ResourceNotFoundException("No IRL account has this number"));
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
