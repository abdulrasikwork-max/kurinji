package com.safesphere.backend.push.repository;

import com.safesphere.backend.push.entity.PushMessage;
import com.safesphere.backend.push.entity.PushType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PushMessageRepository extends JpaRepository<PushMessage, Long> {

    @Query("SELECT m.id FROM PushMessage m WHERE m.status = com.safesphere.backend.push.entity.PushMessage.Status.QUEUED"
            + " AND m.nextAttemptAt <= :now ORDER BY m.nextAttemptAt, m.id")
    List<Long> findDueIds(@Param("now") LocalDateTime now, Pageable page);

    /** Events (not rows: one event reaches every phone) of these kinds for a person since a time, queued or sent. */
    @Query("SELECT COUNT(DISTINCT m.eventId) FROM PushMessage m WHERE m.userId = :userId AND m.type IN :types"
            + " AND m.createdAt >= :since AND m.status IN (com.safesphere.backend.push.entity.PushMessage.Status.QUEUED,"
            + " com.safesphere.backend.push.entity.PushMessage.Status.SENT)")
    long countEventsSince(@Param("userId") long userId, @Param("types") Collection<PushType> types,
                          @Param("since") LocalDateTime since);

    /** Is a push of this kind about this subject still waiting for this person? (batching) */
    @Query("SELECT COUNT(m) > 0 FROM PushMessage m WHERE m.userId = :userId AND m.type = :type AND m.subject = :subject"
            + " AND m.status = com.safesphere.backend.push.entity.PushMessage.Status.QUEUED")
    boolean existsQueued(@Param("userId") long userId, @Param("type") PushType type, @Param("subject") String subject);

    /** Was this person told about this subject (queued or sent) after a time? ("once until you open Replies") */
    @Query("SELECT COUNT(m) > 0 FROM PushMessage m WHERE m.userId = :userId AND m.type = :type AND m.subject = :subject"
            + " AND m.createdAt > :since AND m.status IN (com.safesphere.backend.push.entity.PushMessage.Status.QUEUED,"
            + " com.safesphere.backend.push.entity.PushMessage.Status.SENT)")
    boolean existsSince(@Param("userId") long userId, @Param("type") PushType type, @Param("subject") String subject,
                        @Param("since") LocalDateTime since);

    /** Still-waiting pushes of this kind about this subject (On my way: a check-in or the end makes them pointless). */
    @Modifying
    @Query("DELETE FROM PushMessage m WHERE m.userId = :userId AND m.type = :type AND m.subject = :subject"
            + " AND m.status = com.safesphere.backend.push.entity.PushMessage.Status.QUEUED")
    int deleteQueued(@Param("userId") long userId, @Param("type") PushType type, @Param("subject") String subject);

    @Modifying
    @Query("DELETE FROM PushMessage m WHERE m.createdAt < :before")
    int deleteCreatedBefore(@Param("before") LocalDateTime before);

    @Modifying
    @Query("DELETE FROM PushMessage m WHERE m.deviceId IN :deviceIds")
    int deleteByDeviceIds(@Param("deviceIds") Collection<Long> deviceIds);

    List<PushMessage> findByUserIdOrderByIdAsc(Long userId);
}
