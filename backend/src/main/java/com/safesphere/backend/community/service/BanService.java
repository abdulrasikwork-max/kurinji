package com.safesphere.backend.community.service;

import com.safesphere.backend.auth.phone.PhoneNumbers;
import com.safesphere.backend.common.exception.ConflictException;
import com.safesphere.backend.common.exception.InvalidRequestException;
import com.safesphere.backend.common.exception.ResourceNotFoundException;
import com.safesphere.backend.community.dto.CommunityDtos.BanRequest;
import com.safesphere.backend.community.dto.CommunityDtos.BanView;
import com.safesphere.backend.community.dto.CommunityDtos.EndRequest;
import com.safesphere.backend.community.entity.Ban;
import com.safesphere.backend.community.entity.Community;
import com.safesphere.backend.community.entity.CommunityAction;
import com.safesphere.backend.community.entity.CommunityEnums.ActionKind;
import com.safesphere.backend.community.entity.CommunityEnums.BanReason;
import com.safesphere.backend.community.entity.CommunityEnums.EndReason;
import com.safesphere.backend.community.entity.Membership;
import com.safesphere.backend.community.repository.BanRepository;
import com.safesphere.backend.community.repository.CommunityActionRepository;
import com.safesphere.backend.community.repository.MembershipRepository;
import com.safesphere.backend.community.repository.VerificationRequestRepository;
import com.safesphere.backend.user.entity.User;
import com.safesphere.backend.user.repository.UserRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase N3 (N-4): bans and the admin's end of a membership.
 *
 * A ban is on a roll number and/or a phone number, stored only as their HMACs. It ends the membership of every account
 * it hits at once (BANNED; any verifier flag goes), deletes their open request (and its photo), and refuses any later
 * verification with that roll number or from that phone (VerificationService, DecisionService). It NEVER touches the
 * account itself: sign-in, sessions, SOS, Safe Walk and the Safety Circle keep working (CLAUDE.md §1, §11; no safety
 * package reads this one, CommunityIsolationTest). It survives the person's account deletion. Lifting it lets the
 * person ask to be verified again (their membership stays ended until a new decision).
 */
@Service
public class BanService {

    private final UserRepository users;
    private final MembershipRepository memberships;
    private final VerificationRequestRepository requests;
    private final CommunityActionRepository actions;
    private final BanRepository bans;
    private final CommunityKeys keys;
    private final MembershipLookup lookup;
    private final DecisionService decisions;
    private final CommunityAdminService admin;
    private final IdPhotoStore photos;
    private final Clock clock;

    public BanService(UserRepository users, MembershipRepository memberships, VerificationRequestRepository requests,
                      CommunityActionRepository actions, BanRepository bans, CommunityKeys keys,
                      MembershipLookup lookup, DecisionService decisions, CommunityAdminService admin,
                      IdPhotoStore photos, Clock clock) {
        this.users = users;
        this.memberships = memberships;
        this.requests = requests;
        this.actions = actions;
        this.bans = bans;
        this.keys = keys;
        this.lookup = lookup;
        this.decisions = decisions;
        this.admin = admin;
        this.photos = photos;
        this.clock = clock;
    }

    @Transactional
    public BanView ban(long adminId, BanRequest request) {
        decisions.requireAdmin(adminId);
        if (request == null) throw new InvalidRequestException("Give a roll number, a phone number, or both");
        BanReason reason = Arrays.stream(BanReason.values()).filter(r -> r.name().equals(request.reason()))
                .findFirst().orElseThrow(() -> new InvalidRequestException("Choose a reason"));
        String note = request.note() == null || request.note().isBlank() ? null : request.note().trim();
        if (note != null && note.length() > 200) throw new InvalidRequestException("Keep the note under 200 characters");
        String rollKey = null;
        if (request.rollNumber() != null && !request.rollNumber().isBlank()) {
            String roll = CommunityRules.normaliseRoll(request.rollNumber());
            if (!CommunityRules.isRoll(roll)) throw new InvalidRequestException("This is not a roll number");
            rollKey = keys.rollKey(Community.MIET, roll);
        }
        String phone = null;
        if (request.phone() != null && !request.phone().isBlank()) {
            phone = PhoneNumbers.normalize(request.phone());
            if (phone == null) throw new InvalidRequestException("This is not a mobile number");
        }
        if (rollKey == null && phone == null) {
            throw new InvalidRequestException("Give a roll number, a phone number, or both");
        }
        long miet = lookup.miet().getId();
        LocalDateTime now = now();

        Set<Long> hit = new LinkedHashSet<>();
        if (rollKey != null) memberships.findByCommunityIdAndRollKey(miet, rollKey).ifPresent(m -> hit.add(m.getUserId()));
        if (phone != null) {
            users.findByPhoneE164AndPhoneVerifiedAtIsNotNull(phone).filter(u -> !u.isErased())
                    .ifPresent(u -> hit.add(u.getId()));
        }
        if (hit.contains(adminId)) throw new InvalidRequestException("You cannot ban yourself");

        Ban ban = bans.saveAndFlush(new Ban(miet, rollKey, phone == null ? null : keys.phoneKey(phone), reason, note,
                adminId, now));
        for (long userId : hit) {
            memberships.findByUserIdForUpdate(userId).ifPresent(m -> {
                m.end(EndReason.BANNED, now);
                memberships.saveAndFlush(m);
            });
            requests.findByUserIdForUpdate(userId).ifPresent(r -> {
                requests.delete(r);
                photos.deleteAfterCommit(r.getPhotoKey());
            });
            actions.save(new CommunityAction(miet, adminId, userId, ActionKind.BANNED, null, reason.name(), null, now));
        }
        if (hit.isEmpty()) {
            actions.save(new CommunityAction(miet, adminId, null, ActionKind.BANNED, null, reason.name(), null, now));
        }
        return view(ban);
    }

    @Transactional
    public BanView lift(long adminId, long banId) {
        decisions.requireAdmin(adminId);
        long miet = lookup.miet().getId();
        Ban ban = bans.findById(banId).filter(b -> b.getCommunityId() == miet)
                .orElseThrow(() -> new ResourceNotFoundException("Ban not found"));
        if (!ban.isInForce()) return view(ban);
        LocalDateTime now = now();
        ban.lift(adminId, now);
        bans.saveAndFlush(ban);
        Long subject = person(ban).map(Membership::getUserId).orElse(null);
        actions.save(new CommunityAction(miet, adminId, subject, ActionKind.BAN_LIFTED, null, ban.getReason().name(),
                null, now));
        return view(ban);
    }

    @Transactional(readOnly = true)
    public List<BanView> bans(long adminId) {
        decisions.requireAdmin(adminId);
        return bans.newestFirst(lookup.miet().getId(), PageRequest.of(0, 100)).stream().map(this::view).toList();
    }

    /** An admin ends someone's active membership (REMOVED), for example after a wrong verification. Logged. */
    @Transactional
    public void endMembership(long adminId, EndRequest request) {
        decisions.requireAdmin(adminId);
        User target = admin.byPhone(request == null ? null : request.phone());
        if (target.getId() == adminId) throw new InvalidRequestException("You cannot end your own membership here");
        LocalDateTime now = now();
        Membership m = memberships.findByUserIdForUpdate(target.getId()).filter(x -> x.isActive(now))
                .orElseThrow(() -> new ConflictException("This person has no active membership"));
        m.end(EndReason.REMOVED, now);
        memberships.saveAndFlush(m);
        actions.save(new CommunityAction(m.getCommunityId(), adminId, target.getId(), ActionKind.ENDED, null,
                EndReason.REMOVED.name(), null, now));
    }

    private Optional<Membership> person(Ban ban) {
        if (ban.getRollKey() == null) return Optional.empty();
        return memberships.findByCommunityIdAndRollKey(ban.getCommunityId(), ban.getRollKey());
    }

    private BanView view(Ban b) {
        String person = person(b).flatMap(m -> users.findById(m.getUserId())).map(User::getName).orElse(null);
        String by = users.findById(b.getBannedBy()).map(User::getName).orElse("Deleted account");
        return new BanView(b.getId(), b.getReason().name(), b.getNote(), b.getRollKey() != null,
                b.getPhoneKey() != null, person, by, b.getCreatedAt().toInstant(ZoneOffset.UTC),
                b.getLiftedAt() == null ? null : b.getLiftedAt().toInstant(ZoneOffset.UTC));
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
