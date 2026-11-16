package com.safesphere.backend.community.service;

import com.safesphere.backend.auth.phone.PhoneNumbers;
import com.safesphere.backend.common.exception.ConflictException;
import com.safesphere.backend.common.exception.ForbiddenException;
import com.safesphere.backend.common.exception.InvalidRequestException;
import com.safesphere.backend.common.exception.ResourceNotFoundException;
import com.safesphere.backend.common.exception.TooManyAttemptsException;
import com.safesphere.backend.community.dto.CommunityDtos.DecisionRequest;
import com.safesphere.backend.community.dto.CommunityDtos.DecisionResult;
import com.safesphere.backend.community.dto.CommunityDtos.LookupRequest;
import com.safesphere.backend.community.dto.CommunityDtos.RequestDetail;
import com.safesphere.backend.community.entity.Community;
import com.safesphere.backend.community.entity.CommunityAction;
import com.safesphere.backend.community.entity.CommunityEnums.ActionKind;
import com.safesphere.backend.community.entity.CommunityEnums.Method;
import com.safesphere.backend.community.entity.CommunityEnums.RefusalReason;
import com.safesphere.backend.community.entity.Membership;
import com.safesphere.backend.community.entity.VerificationRequest;
import com.safesphere.backend.community.repository.CommunityActionRepository;
import com.safesphere.backend.community.repository.MembershipRepository;
import com.safesphere.backend.community.repository.VerificationRequestRepository;
import com.safesphere.backend.config.CommunityProperties;
import com.safesphere.backend.security.AttemptLimiter;
import com.safesphere.backend.user.entity.User;
import com.safesphere.backend.user.entity.UserRole;
import com.safesphere.backend.user.repository.UserRepository;
import com.safesphere.backend.user.service.RealNamePolicy;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Phase N3: the decision on a verification request, by a person who saw the college ID.
 *
 *   In person (verifiers and admins, /api/verifier/**): the applicant shows a code; typing it CLAIMS the request for the
 *   one who typed it (claim-lifetime, 15 min); only that person may decide it. Wrong codes are limited per decider.
 *   ID photo (admins only, /api/admin/community/**): the oldest-first queue, the decrypted photo, the decision.
 *
 * Every approval needs the roll number typed from the ID card: its HMAC must equal the one of what the applicant typed
 * (otherwise 409, retype or refuse ROLL_MISMATCH). The server then refuses by itself when the roll number or the phone
 * number is banned (BANNED) or the roll number already belongs to another account (ALREADY_MEMBER). Nobody decides
 * their own request. The request row is deleted at the decision (its photo file right after the commit); the log keeps
 * who decided what and why. A corrected name is logged with its earlier spelling and shown to the person; approval
 * locks the name (N-24).
 */
@Service
public class DecisionService {

    static final String NOT_FOUND = "That code is not right or has expired. Ask the person to show a new code.";

    private final UserRepository users;
    private final MembershipRepository memberships;
    private final VerificationRequestRepository requests;
    private final CommunityActionRepository actions;
    private final CommunityKeys keys;
    private final MembershipLookup lookup;
    private final VerificationService verification;
    private final IdPhotoStore photos;
    private final CommunityProperties properties;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final AttemptLimiter wrongCodes;

    public DecisionService(UserRepository users, MembershipRepository memberships,
                           VerificationRequestRepository requests, CommunityActionRepository actions,
                           CommunityKeys keys, MembershipLookup lookup, VerificationService verification,
                           IdPhotoStore photos, CommunityProperties properties, Clock clock,
                           PlatformTransactionManager transactions) {
        this.users = users;
        this.memberships = memberships;
        this.requests = requests;
        this.actions = actions;
        this.keys = keys;
        this.lookup = lookup;
        this.verification = verification;
        this.photos = photos;
        this.properties = properties;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactions);
        this.wrongCodes = new AttemptLimiter(properties.wrongCodes(), properties.wrongCodeWindow(), 100_000, clock);
    }

    // ---- in person ----------------------------------------------------------------------------------------------

    /** POST /api/verifier/lookup: the code the applicant shows → who they are (never the roll number); claims it. */
    @Transactional
    public RequestDetail lookup(long deciderId, LookupRequest body) {
        User decider = requireDecider(deciderId);
        String limiterKey = "d:" + deciderId;
        if (wrongCodes.isBlocked(limiterKey)) {
            throw new TooManyAttemptsException("Too many wrong codes. Wait a few minutes and try again.");
        }
        LocalDateTime now = now();
        String code = CommunityRules.normaliseCode(body == null ? null : body.code());
        Optional<VerificationRequest> found = code == null ? Optional.empty()
                : requests.findByCodeHashForUpdate(keys.codeHash(VerificationService.CODE_PURPOSE, code))
                .filter(r -> r.getMethod() == Method.IN_PERSON && r.hasLiveCode(now) && !r.isExpired(now));
        if (found.isEmpty()) {
            wrongCodes.record(limiterKey);
            throw new ResourceNotFoundException(NOT_FOUND);
        }
        VerificationRequest request = found.get();
        if (request.getUserId().equals(decider.getId())) throw new ForbiddenException("Nobody can verify themselves");
        request.claim(deciderId, now);
        requests.saveAndFlush(request);
        return detail(request);
    }

    /** POST /api/verifier/requests/{ref}/decision: only the one who typed the code, within the claim's life. */
    public DecisionResult decideInPerson(long deciderId, String ref, DecisionRequest body) {
        return decide(deciderId, ref, body, Method.IN_PERSON);
    }

    // ---- ID photo (admins) ----------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<RequestDetail> photoQueue(long adminId) {
        requireAdmin(adminId);
        return requests.photoQueue(Method.ID_PHOTO, now(), PageRequest.of(0, 50)).stream().map(this::detail).toList();
    }

    /** The decrypted ID photo of a request in the queue (served with no-store, never cached). */
    @Transactional(readOnly = true)
    public byte[] photo(long adminId, String ref) {
        requireAdmin(adminId);
        LocalDateTime now = now();
        VerificationRequest request = requests.findByPublicId(ref)
                .filter(r -> r.getMethod() == Method.ID_PHOTO && r.getPhotoKey() != null && !r.isExpired(now))
                .orElseThrow(() -> new ResourceNotFoundException("Request not found"));
        if (request.getUserId() == adminId) throw new ForbiddenException("Nobody can verify themselves");
        return photos.read(request.getPhotoKey());
    }

    /** POST /api/admin/community/requests/{ref}/decision. */
    public DecisionResult decideByPhoto(long adminId, String ref, DecisionRequest body) {
        return decide(adminId, ref, body, Method.ID_PHOTO);
    }

    // ---- the decision ---------------------------------------------------------------------------------------------

    private DecisionResult decide(long deciderId, String ref, DecisionRequest body, Method method) {
        if (body == null || body.approve() == null) throw new InvalidRequestException("Approve or refuse");
        // Checked before any lock: wrong input never touches the request.
        RefusalReason reason = null;
        String rollKey = null;
        String correctedName = null;
        if (body.approve()) {
            String roll = CommunityRules.normaliseRoll(body.rollNumber());
            if (!CommunityRules.isRoll(roll)) {
                throw new InvalidRequestException("Type the roll number exactly as it is printed on the ID card");
            }
            rollKey = keys.rollKey(Community.MIET, roll);
            if (body.name() != null && !body.name().isBlank()) {
                correctedName = RealNamePolicy.clean(body.name());
                RealNamePolicy.Result check = RealNamePolicy.check(correctedName);
                if (check != RealNamePolicy.Result.OK) {
                    throw new InvalidRequestException(RealNamePolicy.message(check));
                }
            }
        } else {
            reason = Arrays.stream(RefusalReason.values()).filter(r -> r.name().equals(body.reason()))
                    .filter(RefusalReason::choosable).findFirst()
                    .orElseThrow(() -> new InvalidRequestException("Choose why you refuse"));
        }
        RefusalReason chosen = reason;
        String typedRoll = rollKey;
        String name = correctedName;
        try {
            return tx.execute(status -> decideLocked(deciderId, ref, method, chosen, typedRoll, name));
        } catch (DataIntegrityViolationException e) {
            // Two decisions on the same roll number at the same moment: nothing was saved; the next try sees the other.
            throw new ConflictException("Someone else just decided about this roll number. Try again.");
        }
    }

    private DecisionResult decideLocked(long deciderId, String ref, Method method, RefusalReason chosen,
                                        String typedRollKey, String correctedName) {
        User decider = method == Method.ID_PHOTO ? requireAdmin(deciderId) : requireDecider(deciderId);
        LocalDateTime now = now();
        VerificationRequest request = requests.findByPublicIdForUpdate(ref)
                .filter(r -> r.getMethod() == method && !r.isExpired(now))
                .orElseThrow(() -> new ResourceNotFoundException(method == Method.IN_PERSON ? NOT_FOUND
                        : "Request not found"));
        if (request.getUserId().equals(decider.getId())) throw new ForbiddenException("Nobody can verify themselves");
        if (method == Method.IN_PERSON
                && !request.claimedBy(deciderId, now.minus(properties.claimLifetime()))) {
            throw new ResourceNotFoundException(NOT_FOUND);
        }
        if (method == Method.ID_PHOTO && request.getPhotoKey() == null) {
            throw new ConflictException("There is no photo to decide on yet");
        }
        User applicant = users.findByIdForUpdate(request.getUserId())
                .filter(u -> u.isEnabled() && !u.isErased())
                .orElseThrow(() -> new ResourceNotFoundException("Request not found"));
        Community miet = lookup.miet();

        if (chosen != null) return finish(request, decider, applicant, ActionKind.REFUSED, chosen.name(), now);

        if (!typedRollKey.equals(request.getRollKey())) {
            if (!wrongCodes.tryAcquire("d:" + deciderId)) {
                throw new TooManyAttemptsException("Too many wrong tries. Wait a few minutes and try again.");
            }
            throw new ConflictException("The roll number does not match what the person typed. Check the card and "
                    + "type it again, or refuse.");
        }
        if (decider.getRole() != UserRole.ADMIN && actions.countByActorIdAndActionAndCreatedAtAfter(deciderId,
                ActionKind.VERIFIED, now.minus(Duration.ofDays(1))) >= properties.approvalsPerDay()) {
            throw new TooManyAttemptsException("You have approved the most people allowed for one day. "
                    + "An admin can continue.");
        }
        if (verification.banOn(miet.getId(), applicant.getPhoneE164(), request.getRollKey()).isPresent()) {
            return finish(request, decider, applicant, ActionKind.REFUSED, RefusalReason.BANNED.name(), now);
        }
        Optional<Membership> holder = memberships.findByCommunityIdAndRollKey(miet.getId(), request.getRollKey());
        if (holder.isPresent() && !holder.get().getUserId().equals(applicant.getId())) {
            return finish(request, decider, applicant, ActionKind.REFUSED, RefusalReason.ALREADY_MEMBER.name(), now);
        }

        LocalDateTime endsAt = CommunityRules.endsAt(request.getGraduationYear());
        Optional<Membership> own = memberships.findByUserIdForUpdate(applicant.getId());
        if (own.isPresent()) {
            if (own.get().isActive(now)) throw new ConflictException("This person is already a verified member");
            own.get().verify(miet.getId(), request.getRollKey(), request.getProgramme(), request.getGraduationYear(),
                    method, deciderId, now, endsAt);
            memberships.saveAndFlush(own.get());
        } else {
            memberships.saveAndFlush(new Membership(applicant.getId(), miet.getId(), request.getRollKey(),
                    request.getProgramme(), request.getGraduationYear(), method, deciderId, now, endsAt));
        }
        if (correctedName != null && !correctedName.equals(applicant.getName())) {
            actions.save(new CommunityAction(miet.getId(), deciderId, applicant.getId(), ActionKind.NAME_CORRECTED,
                    method, null, applicant.getName(), now));
            applicant.rename(correctedName);
        }
        applicant.markNameChecked(now);
        users.saveAndFlush(applicant);
        return finish(request, decider, applicant, ActionKind.VERIFIED, null, now);
    }

    /** Logs the decision, deletes the request, and the photo file after the commit. */
    private DecisionResult finish(VerificationRequest request, User decider, User applicant, ActionKind kind,
                                  String reason, LocalDateTime now) {
        actions.save(new CommunityAction(request.getCommunityId(), decider.getId(), applicant.getId(), kind,
                request.getMethod(), reason, null, now));
        requests.delete(request);
        requests.flush();
        actions.flush();
        photos.deleteAfterCommit(request.getPhotoKey());
        return new DecisionResult(kind.name(), reason);
    }

    // ---- who may decide -------------------------------------------------------------------------------------------

    /** An admin, or an active verifier (SecurityConfig checks the same from the database; checked again here). */
    User requireDecider(long userId) {
        User user = active(userId);
        if (user.getRole() == UserRole.ADMIN || lookup.isActiveVerifier(userId)) return user;
        throw new ForbiddenException("Only IRL verifiers can do this");
    }

    User requireAdmin(long userId) {
        User user = active(userId);
        if (user.getRole() != UserRole.ADMIN) throw new ForbiddenException("Only IRL admins can do this");
        return user;
    }

    private User active(long userId) {
        return users.findById(userId).filter(u -> u.isEnabled() && !u.isErased())
                .orElseThrow(() -> new ForbiddenException("Only IRL verifiers can do this"));
    }

    private RequestDetail detail(VerificationRequest r) {
        User applicant = users.findById(r.getUserId()).orElseThrow();
        return new RequestDetail(r.getPublicId(), r.getMethod().name(), applicant.getName(),
                PhoneNumbers.hint(applicant.getPhoneE164()), r.getProgramme().name(), r.getProgramme().label(),
                r.getGraduationYear(), r.getCreatedAt().toInstant(ZoneOffset.UTC),
                r.getPhotoUploadedAt() == null ? null : r.getPhotoUploadedAt().toInstant(ZoneOffset.UTC));
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
