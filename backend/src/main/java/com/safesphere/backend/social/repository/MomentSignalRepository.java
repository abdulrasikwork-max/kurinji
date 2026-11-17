package com.safesphere.backend.social.repository;

import com.safesphere.backend.social.entity.MomentSignal;
import com.safesphere.backend.social.entity.ResponseEnums.SignalKind;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Phase N5: Same and Say more. Counts here are internal (bands, ResponsePolicy); no answer carries a number. */
public interface MomentSignalRepository extends JpaRepository<MomentSignal, Long> {

    Optional<MomentSignal> findByMomentIdAndUserIdAndKind(long momentId, long userId, SignalKind kind);

    /** The signals of one kind on one moment, newest first (the author's list of names). */
    List<MomentSignal> findByMomentIdAndKindOrderByCreatedAtDescIdDesc(long momentId, SignalKind kind);

    /** [userId]'s own signals on a list of moments (the viewer's state on a page of Today). */
    List<MomentSignal> findByUserIdAndMomentIdIn(long userId, Collection<Long> momentIds);

    /** All signals of one kind on a list of moments (bands and the author's lines for a page). */
    List<MomentSignal> findByKindAndMomentIdIn(SignalKind kind, Collection<Long> momentIds);

    List<MomentSignal> findByUserIdAndKind(long userId, SignalKind kind);

    boolean existsByMomentIdAndKind(long momentId, SignalKind kind);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM MomentSignal s WHERE s.momentId IN :momentIds")
    int deleteByMomentIdIn(@Param("momentIds") Collection<Long> momentIds);
}
