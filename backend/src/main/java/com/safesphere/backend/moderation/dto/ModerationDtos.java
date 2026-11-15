package com.safesphere.backend.moderation.dto;

import com.safesphere.backend.moderation.entity.ModerationEnums.ActionReason;
import com.safesphere.backend.moderation.entity.ModerationEnums.ActionType;
import com.safesphere.backend.moderation.entity.ModerationEnums.AppealStatus;
import com.safesphere.backend.moderation.entity.ModerationEnums.TargetKind;
import com.safesphere.backend.user.entity.UserRole;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

/**
 * Request and response bodies shared by moderation (Phase 7; reduced in N1, when the Nearby signal cases were
 * retired). Responses are FIELD ALLOW-LISTS: no names, e-mails or safety data ever appear in a moderator's view. Only
 * the ADMIN audit-log view carries account ids (for accountability). The content case bodies are in
 * ContentModerationDtos.
 */
public final class ModerationDtos {

    private ModerationDtos() {
    }

    /** What the signed-in person may do (shown or hidden menu entries; the server checks again on every call). */
    public record Access(boolean canModerate, boolean canAdminister) {
    }

    /** Account age as a band, never a date. */
    public enum AccountAge { NEW, RECENT, ESTABLISHED }

    public record AppealDecision(@NotNull(message = "Decision is required") AppealStatus decision) {
    }

    /**
     * ADMIN: change someone's role. [role] may only be USER or MODERATOR (ADMIN is set in the database only). Phase N2:
     * the account is named by its verified mobile number ([phone], typed in any common way).
     */
    public record RoleChange(
            @NotNull(message = "Mobile number is required") String phone,
            @NotNull(message = "Role is required") UserRole role) {
    }

    /**
     * ADMIN: one audit-log entry. Account ids, not names or e-mails. [targetKind] says what a content decision was
     * about (null for a role change).
     */
    public record AuditEntry(String id, long actorId, ActionType action, ActionReason reason, String caseRef,
                             TargetKind targetKind, Long subjectUserId, LocalDateTime restrictedUntil,
                             UserRole newRole, LocalDateTime occurredAt) {
    }
}
