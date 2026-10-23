package com.safesphere.backend.social.repository;

import com.safesphere.backend.social.entity.Connection;
import com.safesphere.backend.social.entity.ConnectionStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConnectionRepository extends JpaRepository<Connection, Long> {

    Optional<Connection> findByUserLowAndUserHigh(Long userLow, Long userHigh);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Connection c where c.userLow = :low and c.userHigh = :high")
    Optional<Connection> findPairForUpdate(@Param("low") Long low, @Param("high") Long high);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Connection c where c.publicId = :publicId")
    Optional<Connection> findByPublicIdForUpdate(@Param("publicId") String publicId);

    @Query("select c from Connection c where (c.userLow = :userId or c.userHigh = :userId) and c.status = :status"
            + " order by c.respondedAt desc, c.requestedAt desc")
    List<Connection> findInvolving(@Param("userId") Long userId, @Param("status") ConnectionStatus status);

    /** Requests waiting for [userId]'s answer (declined ones are not shown again). */
    @Query("select c from Connection c where (c.userLow = :userId or c.userHigh = :userId) and c.requesterId <> :userId"
            + " and c.status = com.safesphere.backend.social.entity.ConnectionStatus.PENDING order by c.requestedAt desc")
    List<Connection> findIncoming(@Param("userId") Long userId);

    /** Requests [userId] sent that were not accepted (a decline looks pending to its sender). */
    @Query("select c from Connection c where c.requesterId = :userId"
            + " and c.status <> com.safesphere.backend.social.entity.ConnectionStatus.ACCEPTED order by c.requestedAt desc")
    List<Connection> findOutgoing(@Param("userId") Long userId);

    @Query("select count(c) from Connection c where c.requesterId = :userId"
            + " and c.status <> com.safesphere.backend.social.entity.ConnectionStatus.ACCEPTED")
    long countOutgoing(@Param("userId") Long userId);

    @Query("select count(c) from Connection c where (c.userLow = :userId or c.userHigh = :userId)"
            + " and c.status = com.safesphere.backend.social.entity.ConnectionStatus.ACCEPTED")
    long countAccepted(@Param("userId") Long userId);
}
