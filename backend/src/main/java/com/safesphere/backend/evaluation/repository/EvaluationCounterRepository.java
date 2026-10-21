package com.safesphere.backend.evaluation.repository;

import com.safesphere.backend.evaluation.entity.EvaluationCounter;
import com.safesphere.backend.evaluation.entity.Metric;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Phase 9B counters. Only the evaluation package uses this (EvaluationIsolationTest): OutcomeRecorder and WeeklySnapshot
 * write, EvaluationService reads for the ADMIN report. Nothing that decides what people see reads it.
 */
public interface EvaluationCounterRepository extends JpaRepository<EvaluationCounter, EvaluationCounter.Key> {

    /** Adds [delta] to an existing row; 0 when the row does not exist yet. */
    @Modifying
    @Query("update EvaluationCounter c set c.occurrences = c.occurrences + :delta where c.key.weekStart = :week "
            + "and c.key.metric = :metric and c.key.dimension = :dimension and c.key.modelVersion = :version")
    int increment(@Param("week") LocalDate week, @Param("metric") Metric metric, @Param("dimension") String dimension,
                  @Param("version") String version, @Param("delta") int delta);

    /** A new row (a plain INSERT: a concurrent insert of the same key fails instead of being overwritten). */
    @Modifying
    @Query(value = "insert into evaluation_counters (week_start, metric, dimension, model_version, occurrences) "
            + "values (:week, :metric, :dimension, :version, :delta)", nativeQuery = true)
    int insert(@Param("week") LocalDate week, @Param("metric") String metric, @Param("dimension") String dimension,
               @Param("version") String version, @Param("delta") int delta);

    boolean existsByKeyWeekStartAndKeyMetric(LocalDate weekStart, Metric metric);

    List<EvaluationCounter> findByKeyWeekStartGreaterThanEqual(LocalDate weekStart);

    @Modifying
    @Query("delete from EvaluationCounter c where c.key.weekStart < :cutoff")
    int deleteOlderThan(@Param("cutoff") LocalDate cutoff);

    @Modifying
    @Query("delete from EvaluationCounter c")
    int deleteEverything();
}
