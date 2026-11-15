package com.safesphere.backend.moderation.service;

import com.safesphere.backend.common.exception.ConflictException;
import com.safesphere.backend.common.exception.ForbiddenException;
import com.safesphere.backend.common.exception.InvalidRequestException;
import com.safesphere.backend.common.exception.ResourceNotFoundException;
import com.safesphere.backend.common.exception.TooManyAttemptsException;
import com.safesphere.backend.config.ModerationProperties;
import com.safesphere.backend.moderation.dto.ModerationDtos.Access;
import com.safesphere.backend.moderation.dto.ModerationDtos.AuditEntry;
import com.safesphere.backend.moderation.dto.ModerationDtos.RoleChange;
import com.safesphere.backend.moderation.entity.ModerationAction;
import com.safesphere.backend.moderation.entity.ModerationEnums.ActionReason;
import com.safesphere.backend.moderation.entity.ModerationEnums.ActionType;
import com.safesphere.backend.moderation.repository.ModerationActionRepository;
import com.safesphere.backend.security.AttemptLimiter;
import com.safesphere.backend.user.entity.User;
import com.safesphere.backend.user.entity.UserRole;
import com.safesphere.backend.user.repository.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who may moderate, and the audit log (Phase 7, docs/PHASE7.md §4; reduced in N1, when the Nearby signal cases, flags,
 * community standing and the evaluation report were retired). What stays here is shared by content moderation
 * (ContentModerationService, 13D):
 *   - the role is re-read from the database on every call (defence in depth behind SecurityConfig), with one request
 *     limit per moderator (limits a stolen moderator token);
 *   - an ADMIN appoints or removes moderators; ADMIN itself is set in the database only;
 *   - every decision is written to the append-only audit log, which only an ADMIN can read and which is deleted by
 *     retention only.
 */
@Service
public class ModerationService {

    private final ModerationActionRepository actions;
    private final UserRepository users;
    private final ModerationProperties properties;
    private final Clock clock;
    private final AttemptLimiter moderatorRequests;

    public ModerationService(ModerationActionRepository actions, UserRepository users, ModerationProperties properties,
                             Clock clock) {
        this.actions = actions;
        this.users = users;
        this.properties = properties;
        this.clock = clock;
        this.moderatorRequests = new AttemptLimiter(properties.moderatorRequestsPerHour(), Duration.ofHours(1),
                10_000, clock);
    }

    /**
     * Periodic part (called by the content moderation upkeep): the audit log's retention and the request limiter's
     * memory. Returns how many rows were deleted.
     */
    @Transactional
    public int maintain() {
        int changed = actions.deleteOlderThan(now().minus(properties.auditRetention()));
        moderatorRequests.evictExpired();
        return changed;
    }

    // ---- Moderators ----------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Access access(long userId) {
        UserRole role = users.findById(userId).filter(User::isEnabled).map(User::getRole).orElse(UserRole.USER);
        return new Access(role.canModerate(), role == UserRole.ADMIN);
    }

    // ---- Administrators --------------------------------------------------------------------------------------------

    /** Appoints or removes a moderator. ADMIN accounts are managed only in the database (no escalation by API). */
    @Transactional
    public void changeRole(long adminId, RoleChange change) {
        requireAdmin(adminId);
        if (change.role() == UserRole.ADMIN) {
            throw new InvalidRequestException("Administrators are appointed in the database only");
        }
        User target = users.findByEmail(change.email().trim().toLowerCase(Locale.ROOT))
                .orElseThrow(() -> new ResourceNotFoundException("Account not found"));
        if (target.getId() == adminId) throw new ConflictException("You cannot change your own role.");
        if (target.getRole() == UserRole.ADMIN) throw new ConflictException("Administrators are managed in the database.");
        if (target.getRole() == change.role()) return;
        target.setRole(change.role());
        users.save(target);
        actions.saveAndFlush(ModerationAction.aboutPerson(adminId,
                change.role() == UserRole.MODERATOR ? ActionType.ROLE_GRANTED : ActionType.ROLE_REVOKED,
                ActionReason.ROLE_CHANGE, target.getId(), change.role(), now()));
    }

    @Transactional(readOnly = true)
    public List<AuditEntry> auditLog(long adminId, int page) {
        requireAdmin(adminId);
        if (page < 0) throw new InvalidRequestException("Page must be 0 or more");
        return actions.findAllByOrderByOccurredAtDescIdDesc(PageRequest.of(page, properties.pageSize())).stream()
                .map(a -> new AuditEntry(a.getPublicId(), a.getActorId(), a.getAction(), a.getReason(), a.getCaseRef(),
                        a.getTargetKind(), a.getSubjectUserId(), a.getRestrictedUntil(), a.getNewRole(),
                        a.getOccurredAt()))
                .toList();
    }

    // ---- Internals -------------------------------------------------------------------------------------------------

    /** Also used by ContentModerationService (13D): one role check and one per-moderator request limit for both. */
    User requireModerator(long userId) {
        User user = users.findById(userId).orElseThrow(() -> new ForbiddenException("Moderators only"));
        if (!user.isEnabled() || !user.getRole().canModerate()) throw new ForbiddenException("Moderators only");
        if (!moderatorRequests.tryAcquire(String.valueOf(userId))) {
            throw new TooManyAttemptsException("Too many moderation requests. Please slow down.");
        }
        return user;
    }

    private void requireAdmin(long userId) {
        User user = requireModerator(userId);
        if (user.getRole() != UserRole.ADMIN) throw new ForbiddenException("Administrators only");
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
}
