package com.safesphere.backend.moderation.entity;

import com.safesphere.backend.moderation.entity.ModerationEnums.CaseOrigin;
import com.safesphere.backend.moderation.entity.ModerationEnums.CaseOutcome;
import com.safesphere.backend.moderation.entity.ModerationEnums.CaseStatus;
import com.safesphere.backend.moderation.entity.ModerationEnums.CoordinationRule;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One review case about one place signal (Phase 7, V13). At most one OPEN case per signal (generated marker + unique
 * key in MySQL; the service checks first). Deleted together with its signal. [version] makes a second moderator acting
 * on the same case at the same moment fail with 409 instead of silently overwriting the first decision.
 */
@Entity
@Table(name = "moderation_cases")
public class ModerationCase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, updatable = false, length = 36, unique = true)
    private String publicId;

    @Column(name = "signal_id", nullable = false, updatable = false)
    private Long signalId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private CaseOrigin origin;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private CaseStatus status = CaseStatus.OPEN;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private CaseOutcome outcome;

    /** Phase 9B (V15): the rule that opened a COORDINATION case; null for other origins and for older cases. */
    @Enumerated(EnumType.STRING)
    @Column(name = "coordination_rule", updatable = false, length = 20)
    private CoordinationRule coordinationRule;

    @Column(name = "opened_at", nullable = false, updatable = false)
    private LocalDateTime openedAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Version
    @Column(nullable = false)
    private long version;

    /** Required by JPA. */
    protected ModerationCase() {
    }

    public ModerationCase(long signalId, CaseOrigin origin, LocalDateTime now) {
        this(signalId, origin, null, now);
    }

    /** [rule] only for COORDINATION cases (V15 CHECK). */
    public ModerationCase(long signalId, CaseOrigin origin, CoordinationRule rule, LocalDateTime now) {
        if (rule != null && origin != CaseOrigin.COORDINATION) {
            throw new IllegalArgumentException("Only a coordination case has a rule");
        }
        this.publicId = UUID.randomUUID().toString();
        this.signalId = signalId;
        this.origin = origin;
        this.coordinationRule = rule;
        this.openedAt = now;
    }

    public void close(CaseOutcome outcome, LocalDateTime now) {
        this.status = CaseStatus.CLOSED;
        this.outcome = outcome;
        this.closedAt = now;
    }

    public boolean isOpen() {
        return status == CaseStatus.OPEN;
    }

    public Long getId() {
        return id;
    }

    public String getPublicId() {
        return publicId;
    }

    public Long getSignalId() {
        return signalId;
    }

    public CaseOrigin getOrigin() {
        return origin;
    }

    public CaseStatus getStatus() {
        return status;
    }

    public CaseOutcome getOutcome() {
        return outcome;
    }

    public CoordinationRule getCoordinationRule() {
        return coordinationRule;
    }

    public LocalDateTime getOpenedAt() {
        return openedAt;
    }

    public LocalDateTime getClosedAt() {
        return closedAt;
    }
}
