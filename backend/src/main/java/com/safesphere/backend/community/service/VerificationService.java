package com.safesphere.backend.community.service;

import com.safesphere.backend.common.exception.AgeNotConfirmedException;
import com.safesphere.backend.common.exception.CommunityBannedException;
import com.safesphere.backend.common.exception.ConflictException;
import com.safesphere.backend.common.exception.InvalidRequestException;
import com.safesphere.backend.common.exception.ResourceNotFoundException;
import com.safesphere.backend.common.exception.TooManyAttemptsException;
import com.safesphere.backend.common.exception.UnauthorizedException;
import com.safesphere.backend.community.dto.CommunityDtos.CommunityView;
import com.safesphere.backend.community.dto.CommunityDtos.MeView;
import com.safesphere.backend.community.dto.CommunityDtos.MembershipView;
import com.safesphere.backend.community.dto.CommunityDtos.NameCorrection;
import com.safesphere.backend.community.dto.CommunityDtos.Outcome;
import com.safesphere.backend.community.dto.CommunityDtos.Programme;
import com.safesphere.backend.community.dto.CommunityDtos.RequestView;
import com.safesphere.backend.community.dto.CommunityDtos.Staff;
import com.safesphere.backend.community.dto.CommunityDtos.StartRequest;
import com.safesphere.backend.community.entity.Ban;
import com.safesphere.backend.community.entity.Community;
import com.safesphere.backend.community.entity.CommunityAction;
import com.safesphere.backend.community.entity.CommunityEnums.ActionKind;
import com.safesphere.backend.community.entity.CommunityEnums.Method;
import com.safesphere.backend.community.entity.Membership;
import com.safesphere.backend.community.entity.VerificationRequest;
import com.safesphere.backend.community.repository.BanRepository;
import com.safesphere.backend.community.repository.CommunityActionRepository;
import com.safesphere.backend.community.repository.MembershipRepository;
import com.safesphere.backend.community.repository.VerificationRequestRepository;
import com.safesphere.backend.config.CommunityProperties;
import com.safesphere.backend.security.AttemptLimiter;
import com.safesphere.backend.social.media.ImageSanitizer;
import com.safesphere.backend.user.entity.User;
import com.safesphere.backend.user.entity.UserRole;
import com.safesphere.backend.user.repository.UserRepository;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Phase N3: the applicant's side of verification, and what the person is shown about their membership.
 *
 * The applicant chooses ONE way (owner, 2026-11-16): IN_PERSON (default) gets a short code to show a verifier together
 * with the physical ID card; ID_PHOTO uploads one photo of the card for an admin. Either way the applicant types their
 * roll number, programme and graduation year; the roll number is turned into its HMAC at once and never stored. Nothing
 * here tells the applicant whether a roll number is already used or banned (nobody can probe roll numbers): that is
 * decided only by a person who saw the ID (DecisionService). A phone number banned from the community is refused here
 * (only the holder of that SIM can be signed in with it).
 */
@Service
public class VerificationService {

    static final String CODE_PURPOSE = "IN_PERSON";
    private static final int MAX_PHOTO_BYTES = 5 * 1024 * 1024;

    private final UserRepository users;
    private final MembershipRepository memberships;
    private final VerificationRequestRepository requests;
    private final CommunityActionRepository actions;
    private final BanRepository bans;
    private final CommunityKeys keys;
    private final MembershipLookup lookup;
    private final IdPhotoStore photos;
    private final CommunityProperties properties;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final AttemptLimiter requestsPerDay;
    private final SecureRandom random = new SecureRandom();

    public VerificationService(UserRepository users, MembershipRepository memberships,
                               VerificationRequestRepository requests, CommunityActionRepository actions,
                               BanRepository bans, CommunityKeys keys, MembershipLookup lookup, IdPhotoStore photos,
                               CommunityProperties properties, Clock clock, PlatformTransactionManager transactions) {
        this.users = users;
        this.memberships = memberships;
        this.requests = requests;
        this.actions = actions;
        this.bans = bans;
        this.keys = keys;
        this.lookup = lookup;
        this.photos = photos;
        this.properties = properties;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactions);
        this.requestsPerDay = new AttemptLimiter(properties.requestsPerDay(), Duration.ofDays(1), 100_000, clock);
    }

    // ---- reading ------------------------------------------------------------------------------------------------

    public List<Programme> programmes() {
        LocalDateTime now = now();
        return Arrays.stream(com.safesphere.backend.community.entity.CommunityEnums.Programme.values())
                .map(p -> new Programme(p.name(), p.label(), p.level().name(), CommunityRules.firstYear(now),
                        CommunityRules.lastYear(p, now)))
                .toList();
    }

    @Transactional(readOnly = true)
    public MeView me(long userId) {
        User user = requireAccount(userId);
        return view(user, null);
    }

    /**
     * The person's state: BANNED (a ban in force on their number or on their membership's roll number) · MEMBER ·
     * PENDING (an open, unexpired request) · ENDED (a membership row that ended) · NONE. [freshCode] = the code of this
     * very answer (start, new code), else null.
     */
    MeView view(User user, String freshCode) {
        LocalDateTime now = now();
        Community miet = lookup.miet();
        CommunityView community = new CommunityView(miet.getCode(), miet.getName());
        Optional<Membership> membership = memberships.findByUserId(user.getId());
        Optional<Ban> ban = banOn(miet.getId(), user.getPhoneE164(), membership.map(Membership::getRollKey).orElse(null));
        boolean admin = user.getRole() == UserRole.ADMIN;
        boolean verifier = membership.map(m -> m.isActive(now) && m.isVerifier()).orElse(false);
        Staff staff = new Staff(verifier, admin);
        MembershipView membershipView = membership.map(this::membershipView).orElse(null);
        NameCorrection correction = latest(user.getId(), ActionKind.NAME_CORRECTED)
                .map(a -> new NameCorrection(a.getDetail(), a.getCreatedAt().toInstant(ZoneOffset.UTC))).orElse(null);

        if (ban.isPresent()) {
            Outcome outcome = new Outcome("BANNED", ban.get().getReason().name(),
                    ban.get().getCreatedAt().toInstant(ZoneOffset.UTC));
            return new MeView("BANNED", community, membershipView, null, outcome, correction, user.isNameLocked(),
                    staff);
        }
        if (membership.isPresent() && membership.get().isActive(now)) {
            return new MeView("MEMBER", community, membershipView, null, null, correction, user.isNameLocked(), staff);
        }
        Optional<VerificationRequest> open = requests.findByUserId(user.getId()).filter(r -> !r.isExpired(now));
        if (open.isPresent()) {
            return new MeView("PENDING", community, membershipView, requestView(open.get(), freshCode, now), null,
                    correction, user.isNameLocked(), staff);
        }
        if (membership.isPresent()) {
            Membership m = membership.get();
            String reason = m.getEndReason() == null ? "GRADUATED" : m.getEndReason().name();
            LocalDateTime at = m.getEndedAt() == null ? m.getEndsAt() : m.getEndedAt();
            return new MeView("ENDED", community, membershipView, null,
                    new Outcome("ENDED", reason, at.toInstant(ZoneOffset.UTC)), correction, user.isNameLocked(), staff);
        }
        Outcome refusal = latest(user.getId(), ActionKind.REFUSED)
                .map(a -> new Outcome("REFUSED", a.getReason(), a.getCreatedAt().toInstant(ZoneOffset.UTC)))
                .orElse(null);
        return new MeView("NONE", community, null, null, refusal, correction, user.isNameLocked(), staff);
    }

    // ---- asking ---------------------------------------------------------------------------------------------------

    /** POST /api/community/verification. */
    public MeView start(long userId, StartRequest request) {
        if (request == null) throw new InvalidRequestException("Choose how you want to be verified");
        Method method = parseMethod(request.method());
        var programme = com.safesphere.backend.community.entity.CommunityEnums.Programme.of(request.programme())
                .orElseThrow(() -> new InvalidRequestException("Choose your programme from the list"));
        LocalDateTime now = now();
        if (request.graduationYear() == null
                || !CommunityRules.yearAllowed(programme, request.graduationYear(), now)) {
            throw new InvalidRequestException("Choose the year you finish your programme, from "
                    + CommunityRules.firstYear(now) + " to " + CommunityRules.lastYear(programme, now));
        }
        String roll = CommunityRules.normaliseRoll(request.rollNumber());
        if (!CommunityRules.isRoll(roll)) {
            throw new InvalidRequestException("Type the roll number exactly as it is printed on your MIET ID card");
        }
        String rollKey = keys.rollKey(Community.MIET, roll);
        roll = null; // the plain roll number goes no further than this line

        String[] issued = new String[1];
        String[] staleKey = new String[1];
        MeView answer = tx.execute(status -> {
            User user = requireAccount(userId);
            Community miet = lookup.miet();
            if (lookup.active(userId).isPresent()) throw new ConflictException("You are already a verified member");
            if (!banOn(miet.getId(), user.getPhoneE164(), null).isEmpty()) throw new CommunityBannedException();
            Optional<VerificationRequest> existing = requests.findByUserIdForUpdate(userId);
            if (existing.isPresent()) {
                if (!existing.get().isExpired(now)) {
                    throw new ConflictException("You already asked to be verified. Withdraw that request to start again.");
                }
                staleKey[0] = existing.get().getPhotoKey();
                requests.delete(existing.get());
                requests.flush();
            }
            if (!requestsPerDay.tryAcquire("u:" + userId)) {
                throw new TooManyAttemptsException("You have asked to be verified too often today. Try again tomorrow.");
            }
            VerificationRequest row = new VerificationRequest(userId, miet.getId(), method, rollKey, programme,
                    request.graduationYear(), now, now.plus(properties.requestLifetime()));
            if (method == Method.IN_PERSON) issued[0] = issueCode(row, now);
            requests.saveAndFlush(row);
            if (staleKey[0] != null) photos.deleteAfterCommit(staleKey[0]);
            return view(user, issued[0]);
        });
        return answer;
    }

    /** POST /api/community/verification/code: a new in-person code (the old one stops working). */
    @Transactional
    public MeView newCode(long userId) {
        User user = requireAccount(userId);
        LocalDateTime now = now();
        VerificationRequest row = requests.findByUserIdForUpdate(userId)
                .filter(r -> !r.isExpired(now) && r.getMethod() == Method.IN_PERSON)
                .orElseThrow(() -> new ResourceNotFoundException("You have no in-person request"));
        String code = issueCode(row, now);
        requests.saveAndFlush(row);
        return view(user, code);
    }

    /**
     * PUT /api/community/verification/photo: the one photo of the ID card (a new one replaces the old). Re-encoded
     * without metadata, encrypted, written under a new random name; the old file is deleted after commit.
     */
    public MeView uploadPhoto(long userId, byte[] body) {
        requireAccount(userId);
        LocalDateTime before = now();
        requests.findByUserId(userId).filter(r -> !r.isExpired(before) && r.getMethod() == Method.ID_PHOTO)
                .orElseThrow(() -> new ResourceNotFoundException("You have no ID photo request"));
        byte[] jpeg;
        try {
            jpeg = ImageSanitizer.sanitize(body, MAX_PHOTO_BYTES).jpeg();
        } catch (ImageSanitizer.Refused refused) {
            throw new InvalidRequestException(refused.getMessage());
        }
        String key = IdPhotoStore.newKey();
        photos.write(key, jpeg);
        jpeg = null;
        try {
            return tx.execute(status -> {
                LocalDateTime now = now();
                User user = requireAccount(userId);
                VerificationRequest row = requests.findByUserIdForUpdate(userId)
                        .filter(r -> !r.isExpired(now) && r.getMethod() == Method.ID_PHOTO)
                        .orElseThrow(() -> new ResourceNotFoundException("You have no ID photo request"));
                String old = row.getPhotoKey();
                row.photo(key, now);
                requests.saveAndFlush(row);
                photos.deleteAfterCommit(old);
                return view(user, null);
            });
        } catch (RuntimeException e) {
            photos.delete(key); // the row never pointed at it
            throw e;
        }
    }

    /** DELETE /api/community/verification: the request goes, and its photo file right after. Same answer if none. */
    @Transactional
    public void withdraw(long userId) {
        requireAccount(userId);
        requests.findByUserIdForUpdate(userId).ifPresent(row -> {
            requests.delete(row);
            photos.deleteAfterCommit(row.getPhotoKey());
        });
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------

    /** A signed-in, active, adult account (the same entry rule as the social features, without membership). */
    User requireAccount(long userId) {
        User user = users.findById(userId).filter(u -> u.isEnabled() && !u.isErased())
                .orElseThrow(() -> new UnauthorizedException("Account is not available"));
        if (!user.isAgeConfirmed()) throw new AgeNotConfirmedException();
        return user;
    }

    /** The newest ban in force on [phoneE164] or on [rollKey] (either may be null). */
    Optional<Ban> banOn(long communityId, String phoneE164, String rollKey) {
        if (rollKey != null) {
            List<Ban> onRoll = bans.inForceOnRoll(communityId, rollKey);
            if (!onRoll.isEmpty()) return Optional.of(onRoll.get(0));
        }
        if (phoneE164 != null) {
            List<Ban> onPhone = bans.inForceOnPhone(communityId, keys.phoneKey(phoneE164));
            if (!onPhone.isEmpty()) return Optional.of(onPhone.get(0));
        }
        return Optional.empty();
    }

    private String issueCode(VerificationRequest row, LocalDateTime now) {
        for (int attempt = 0; attempt < 5; attempt++) {
            String code = CommunityRules.newCode(random);
            String hash = keys.codeHash(CODE_PURPOSE, code);
            if (!requests.existsByCodeHash(hash)) {
                row.newCode(hash, now.plus(properties.codeLifetime()));
                return code;
            }
        }
        throw new IllegalStateException("No free code after 5 tries");
    }

    private Optional<CommunityAction> latest(long userId, ActionKind kind) {
        return actions.aboutPerson(userId, List.of(kind), PageRequest.of(0, 1)).stream().findFirst();
    }

    private MembershipView membershipView(Membership m) {
        return new MembershipView(m.getProgramme().name(), m.getProgramme().label(), m.getGraduationYear(),
                m.getMethod().name(), m.getVerifiedAt().toInstant(ZoneOffset.UTC),
                m.getEndsAt().toInstant(ZoneOffset.UTC),
                m.getEndedAt() == null ? null : m.getEndedAt().toInstant(ZoneOffset.UTC),
                m.getEndReason() == null ? null : m.getEndReason().name());
    }

    private static RequestView requestView(VerificationRequest r, String freshCode, LocalDateTime now) {
        boolean alive = r.hasLiveCode(now);
        long expiresIn = alive ? Math.max(0, Duration.between(now, r.getCodeExpiresAt()).toSeconds()) : 0;
        return new RequestView(r.getMethod().name(), r.getProgramme().name(), r.getProgramme().label(),
                r.getGraduationYear(), freshCode == null ? null : CommunityRules.display(freshCode), alive, expiresIn,
                r.getPhotoUploadedAt() != null, r.getExpiresAt().toInstant(ZoneOffset.UTC));
    }

    private static Method parseMethod(String raw) {
        if (raw == null || raw.isBlank()) return Method.IN_PERSON; // the preferred way is the default
        return Arrays.stream(Method.values()).filter(m -> m.name().equals(raw)).findFirst()
                .orElseThrow(() -> new InvalidRequestException("Choose in person or an ID photo"));
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
