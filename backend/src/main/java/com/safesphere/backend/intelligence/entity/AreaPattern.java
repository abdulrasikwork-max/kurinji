package com.safesphere.backend.intelligence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

/**
 * One row of area_patterns (V14, Phase 8C): the pattern memory of one ~1 km area cell for one group. Deliberately
 * holds no link to any signal or person (docs/PHASE8_DESIGN.md §7.4).
 */
@Entity
@Table(name = "area_patterns")
public class AreaPattern {

    @EmbeddedId
    private Key key;

    @Column(name = "pattern_value", nullable = false, columnDefinition = "DECIMAL(5,3)")
    private double value;

    @Column(name = "decayed_on", nullable = false)
    private LocalDate decayedOn;

    @Column(name = "next_episode_on", nullable = false)
    private LocalDate nextEpisodeOn;

    /** Required by JPA. */
    protected AreaPattern() {
    }

    public AreaPattern(String areaCell, String signalGroup) {
        this.key = new Key(areaCell, signalGroup);
    }

    public Key getKey() {
        return key;
    }

    public double getValue() {
        return value;
    }

    public LocalDate getDecayedOn() {
        return decayedOn;
    }

    public LocalDate getNextEpisodeOn() {
        return nextEpisodeOn;
    }

    public void update(double value, LocalDate decayedOn, LocalDate nextEpisodeOn) {
        this.value = value;
        this.decayedOn = decayedOn;
        this.nextEpisodeOn = nextEpisodeOn;
    }

    /** (area cell, group): the primary key. */
    @Embeddable
    public static class Key implements Serializable {

        @Column(name = "area_cell", nullable = false, length = 6)
        private String areaCell;

        @Column(name = "signal_group", nullable = false, length = 12)
        private String signalGroup;

        protected Key() {
        }

        public Key(String areaCell, String signalGroup) {
            this.areaCell = areaCell;
            this.signalGroup = signalGroup;
        }

        public String getAreaCell() {
            return areaCell;
        }

        public String getSignalGroup() {
            return signalGroup;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key other && areaCell.equals(other.areaCell) && signalGroup.equals(other.signalGroup);
        }

        @Override
        public int hashCode() {
            return Objects.hash(areaCell, signalGroup);
        }
    }
}
