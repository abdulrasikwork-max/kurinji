package com.safesphere.backend.community.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One place signal (migration V12): a category at a ~150 m cell. Holds no coordinates, no text, no author and no
 * time: who said what, and when it counts, is in [SignalContribution].
 */
@Entity
@Table(name = "place_signals",
        uniqueConstraints = @UniqueConstraint(name = "uk_place_signals_cell_category", columnNames = {"cell", "category"}))
public class PlaceSignal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, updatable = false, length = 36, unique = true)
    private String publicId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 30)
    private SignalCategory category;

    @Column(nullable = false, updatable = false, length = 7)
    private String cell;

    @Column(name = "area_cell", nullable = false, updatable = false, length = 6)
    private String areaCell;

    /** Phase 7 (V13). */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ModerationState moderation = ModerationState.NONE;

    /** UTC; set only while REMOVED (end of the tombstone). */
    @Column(name = "moderation_until")
    private LocalDateTime moderationUntil;

    /**
     * Phase 8C (V14): set once independent people have confirmed it (MODERATE or HIGH). Only decides whether its
     * natural end is a pattern "episode"; never returned to anyone, deleted with the row.
     */
    @Column(name = "reached_confirmed", nullable = false)
    private boolean reachedConfirmed;

    /** Required by JPA. */
    protected PlaceSignal() {
    }

    public PlaceSignal(SignalCategory category, String cell) {
        this.publicId = UUID.randomUUID().toString();
        this.category = category;
        this.cell = cell;
        this.areaCell = cell.substring(0, 6);
    }

    public Long getId() {
        return id;
    }

    public String getPublicId() {
        return publicId;
    }

    public SignalCategory getCategory() {
        return category;
    }

    public String getCell() {
        return cell;
    }

    public String getAreaCell() {
        return areaCell;
    }

    public ModerationState getModeration() {
        return moderation;
    }

    public LocalDateTime getModerationUntil() {
        return moderationUntil;
    }

    /** True when ordinary viewers may see it at all (the SignalPolicy decides the rest). */
    public boolean isUnmoderated() {
        return moderation == ModerationState.NONE;
    }

    public boolean isReachedConfirmed() {
        return reachedConfirmed;
    }

    public void markReachedConfirmed() {
        this.reachedConfirmed = true;
    }

    public void quarantine() {
        this.moderation = ModerationState.QUARANTINED;
        this.moderationUntil = null;
    }

    public void clearModeration() {
        this.moderation = ModerationState.NONE;
        this.moderationUntil = null;
    }

    public void remove(LocalDateTime until) {
        this.moderation = ModerationState.REMOVED;
        this.moderationUntil = until;
    }
}
