package com.safesphere.backend.community.service;

import com.safesphere.backend.account.service.AccountService;
import com.safesphere.backend.auth.phone.PhoneCodeService;
import com.safesphere.backend.common.exception.ConflictException;
import com.safesphere.backend.common.exception.ForbiddenException;
import com.safesphere.backend.common.exception.InvalidRequestException;
import com.safesphere.backend.common.exception.ResourceNotFoundException;
import com.safesphere.backend.common.exception.TooManyAttemptsException;
import com.safesphere.backend.community.dto.CommunityDtos.DecisionResult;
import com.safesphere.backend.community.dto.CommunityDtos.RelinkCode;
import com.safesphere.backend.community.dto.CommunityDtos.RelinkDecision;
import com.safesphere.backend.community.dto.CommunityDtos.RelinkStart;
import com.safesphere.backend.community.entity.Community;
import com.safesphere.backend.community.entity.CommunityAction;
import com.safesphere.backend.community.entity.CommunityEnums.ActionKind;
import com.safesphere.backend.community.entity.Membership;
import com.safesphere.backend.community.entity.NumberRelink;
import com.safesphere.backend.community.repository.CommunityActionRepository;
import com.safesphere.backend.community.repository.MembershipRepository;
import com.safesphere.backend.community.repository.NumberRelinkRepository;
import com.safesphere.backend.config.CommunityProperties;
import com.safesphere.backend.security.AttemptLimiter;
import com.safesphere.backend.user.repository.UserRepository;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Phase N3 (N-25): moving a member's account to a new phone number. Admin + in person only (owner, 2026-11-16).
 *
 * 1. The person signs in with the NEW number; it has no account, so they get a one-use sign-up ticket (N2). Instead of
 *    signing up they choose "I'm moving from another number": POST /api/community/relink {ticket, rollNumber} uses the
 *    ticket up (proof of the new SIM) and answers a short code (alive app.community.relink-lifetime).
 * 2. An IRL admin sees the college ID in person and types the code and the roll number from the card:
 *    POST /api/admin/community/relinks. The roll number must match what the person typed AND a member's roll number.
 * 3. AccountService.moveToNumber moves the account: every sign-in ends (refused while an SOS or a Safe Walk runs). The
 *    person then signs in with the new number. The old number is free.
 * Nothing tells a requester whether a roll number belongs to a member: only the admin learns it, after seeing the ID.
 */
@Service
public class RelinkService {

    static final String CODE_PURPOSE = "RELINK";

    private final PhoneCodeService phoneCodes;
    private final UserRepository users;
    private final MembershipRepository memberships;
    private final NumberRelinkRepository relinks;
    private final CommunityActionRepository actions;
    private final CommunityKeys keys;
    private final MembershipLookup lookup;
    private final DecisionService decisions;
    private final VerificationService verification;
    private final AccountService accounts;
    private final CommunityProperties properties;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final AttemptLimiter wrongCodes;
    private final SecureRandom random = new SecureRandom();

    public RelinkService(PhoneCodeService phoneCodes, UserRepository users, MembershipRepository memberships,
                         NumberRelinkRepository relinks, CommunityActionRepository actions, CommunityKeys keys,
                         MembershipLookup lookup, DecisionService decisions, VerificationService verification,
                         AccountService accounts, CommunityProperties properties, Clock clock,
                         PlatformTransactionManager transactions) {
        this.phoneCodes = phoneCodes;
        this.users = users;
        this.memberships = memberships;
        this.relinks = relinks;
        this.actions = actions;
        this.keys = keys;
        this.lookup = lookup;
        this.decisions = decisions;
        this.verification = verification;
        this.accounts = accounts;
        this.properties = properties;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactions);
        this.wrongCodes = new AttemptLimiter(properties.wrongCodes(), properties.wrongCodeWindow(), 100_000, clock);
    }

    /** POST /api/community/relink (no sign-in: the sign-up ticket of the new number is the credential). */
    public RelinkCode start(RelinkStart request) {
        String roll = CommunityRules.normaliseRoll(request == null ? null : request.rollNumber());
        if (!CommunityRules.isRoll(roll)) {
            throw new InvalidRequestException("Type the roll number exactly as it is printed on your MIET ID card");
        }
        String rollKey = keys.rollKey(Community.MIET, roll);
        return tx.execute(status -> {
            String phone = phoneCodes.consumeTicket(request.ticket());
            if (users.findByPhoneE164AndPhoneVerifiedAtIsNotNull(phone).isPresent()) {
                throw new ConflictException("This number already has an IRL account");
            }
            relinks.findByPhoneE164(phone).ifPresent(old -> {
                relinks.delete(old);
                relinks.flush();
            });
            LocalDateTime now = now();
            String code = null;
            String hash = null;
            for (int attempt = 0; attempt < 5 && code == null; attempt++) {
                String candidate = CommunityRules.newCode(random);
                String candidateHash = keys.codeHash(CODE_PURPOSE, candidate);
                if (!relinks.existsByCodeHash(candidateHash)) {
                    code = candidate;
                    hash = candidateHash;
                }
            }
            if (code == null) throw new IllegalStateException("No free code after 5 tries");
            relinks.saveAndFlush(new NumberRelink(lookup.miet().getId(), phone, rollKey, hash, now,
                    now.plus(properties.relinkLifetime())));
            return new RelinkCode(CommunityRules.display(code), properties.relinkLifetime().toSeconds());
        });
    }

    /** POST /api/admin/community/relinks: an admin who sees the ID in person moves the account. */
    @Transactional
    public DecisionResult decide(long adminId, RelinkDecision request) {
        decisions.requireAdmin(adminId);
        String limiterKey = "r:" + adminId;
        if (wrongCodes.isBlocked(limiterKey)) {
            throw new TooManyAttemptsException("Too many wrong tries. Wait a few minutes and try again.");
        }
        LocalDateTime now = now();
        String code = CommunityRules.normaliseCode(request == null ? null : request.code());
        NumberRelink relink = (code == null ? java.util.Optional.<NumberRelink>empty()
                : relinks.findByCodeHashForUpdate(keys.codeHash(CODE_PURPOSE, code))).filter(r -> !r.isExpired(now))
                .orElseGet(() -> {
                    wrongCodes.record(limiterKey);
                    throw new ResourceNotFoundException("That code is not right or has expired");
                });
        String roll = CommunityRules.normaliseRoll(request.rollNumber());
        if (!CommunityRules.isRoll(roll)) {
            throw new InvalidRequestException("Type the roll number exactly as it is printed on the ID card");
        }
        if (!keys.rollKey(Community.MIET, roll).equals(relink.getRollKey())) {
            wrongCodes.record(limiterKey);
            throw new ConflictException("The roll number does not match what the person typed");
        }
        Membership member = memberships.findByCommunityIdAndRollKey(relink.getCommunityId(), relink.getRollKey())
                .orElseThrow(() -> new ConflictException("No member has this roll number. The person can sign up "
                        + "with the new number and be verified instead."));
        if (member.getUserId() == adminId) throw new ForbiddenException("Nobody can move their own account here");
        if (verification.banOn(relink.getCommunityId(), null, relink.getRollKey()).isPresent()) {
            throw new ConflictException("This roll number is banned. Lift the ban first if that is right.");
        }
        accounts.moveToNumber(member.getUserId(), relink.getPhoneE164());
        relinks.delete(relink);
        actions.save(new CommunityAction(relink.getCommunityId(), adminId, member.getUserId(), ActionKind.RELINKED,
                null, null, null, now));
        return new DecisionResult(ActionKind.RELINKED.name(), null);
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
