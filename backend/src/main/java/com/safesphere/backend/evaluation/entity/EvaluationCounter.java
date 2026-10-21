package com.safesphere.backend.evaluation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

/**
 * One weekly, system-wide count (migration V15). The key holds a week, a metric, an upper-case dimension and a model
 * version; nothing about a place, a person, a signal or a time finer than a week.
 */
@Entity
@Table(name = "evaluation_counters")
public class EvaluationCounter {

    @EmbeddedId
    private Key key;

    @Column(name = "occurrences", nullable = false)
    private int occurrences;

    /** Required by JPA. */
    protected EvaluationCounter() {
    }

    public Key getKey() {
        return key;
    }

    public int getOccurrences() {
        return occurrences;
    }

    @Embeddable
    public static class Key implements Serializable {

        @Column(name = "week_start", nullable = false)
        private LocalDate weekStart;

        @Enumerated(EnumType.STRING)
        @Column(nullable = false, length = 40)
        private Metric metric;

        @Column(nullable = false, length = 48)
        private String dimension;

        @Column(name = "model_version", nullable = false, length = 20)
        private String modelVersion;

        /** Required by JPA. */
        protected Key() {
        }

        public Key(LocalDate weekStart, Metric metric, String dimension, String modelVersion) {
            this.weekStart = weekStart;
            this.metric = metric;
            this.dimension = dimension;
            this.modelVersion = modelVersion;
        }

        public LocalDate getWeekStart() {
            return weekStart;
        }

        public Metric getMetric() {
            return metric;
        }

        public String getDimension() {
            return dimension;
        }

        public String getModelVersion() {
            return modelVersion;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && weekStart.equals(k.weekStart) && metric == k.metric
                    && dimension.equals(k.dimension) && modelVersion.equals(k.modelVersion);
        }

        @Override
        public int hashCode() {
            return Objects.hash(weekStart, metric, dimension, modelVersion);
        }
    }
}
