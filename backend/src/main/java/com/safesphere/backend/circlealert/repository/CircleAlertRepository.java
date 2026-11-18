package com.safesphere.backend.circlealert.repository;

import com.safesphere.backend.circlealert.entity.CircleAlert;
import com.safesphere.backend.circlealert.entity.CircleAlertEnums.Channel;
import com.safesphere.backend.circlealert.entity.CircleAlertEnums.Kind;
import com.safesphere.backend.circlealert.entity.CircleAlertEnums.Status;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Looked up by emergency id, client reference or owner; the services check first that the emergency belongs to the
 * caller. N6: the server queue's reads (all counted in the database, so the limits survive a restart).
 */
public interface CircleAlertRepository extends JpaRepository<CircleAlert, Long> {

    List<CircleAlert> findByClientRefIn(Collection<String> clientRefs);

    List<CircleAlert> findByEmergencyIdOrderByOccurredAtAscIdAsc(Long emergencyId);

    List<CircleAlert> findByJourneyIdOrderByOccurredAtAscIdAsc(Long journeyId);

    long countByEmergencyId(Long emergencyId);

    // ---- N6: the server queue -----------------------------------------------------------------------------------

    List<CircleAlert> findByEmergencyIdAndChannelOrderByIdAsc(Long emergencyId, Channel channel);

    boolean existsByEmergencyIdAndChannelAndKind(Long emergencyId, Channel channel, Kind kind);

    /** Every waiting row, oldest due first (the queue is small: a few rows per SOS). */
    List<CircleAlert> findTop100ByChannelAndStatusOrderByNextAttemptAtAscIdAsc(Channel channel, Status status);

    /** First texts that went out recently (for follow-ups and closing texts). */
    List<CircleAlert> findByChannelAndKindAndStatusInAndSentAtAfter(Channel channel, Kind kind,
                                                                    Collection<Status> statuses, LocalDateTime since);

    /** Accepted texts to one number (by its key) since [since]: the per-number limit, across all senders. */
    long countByRecipientKeyAndStatusInAndSentAtAfter(String recipientKey, Collection<Status> statuses,
                                                      LocalDateTime since);

    /** Accepted server texts since [since]: the server-wide limit. */
    long countByChannelAndStatusInAndSentAtAfter(Channel channel, Collection<Status> statuses, LocalDateTime since);

    /** SOS alert rounds of one person since [since] (emergencies that got at least one row that was not skipped). */
    @Query("select count(distinct a.emergencyId) from CircleAlert a where a.userId = :userId"
            + " and a.channel = com.safesphere.backend.circlealert.entity.CircleAlertEnums.Channel.SERVER_SMS"
            + " and a.kind = com.safesphere.backend.circlealert.entity.CircleAlertEnums.Kind.SOS_STARTED"
            + " and a.status <> com.safesphere.backend.circlealert.entity.CircleAlertEnums.Status.SKIPPED"
            + " and a.occurredAt > :since")
    long countRounds(@Param("userId") long userId, @Param("since") LocalDateTime since);

    long countByUserIdAndKindAndOccurredAtAfter(Long userId, Kind kind, LocalDateTime since);

    Optional<CircleAlert> findFirstByUserIdAndKindOrderByIdDesc(Long userId, Kind kind);

    boolean existsByEmergencyIdAndContactIdAndKindIn(Long emergencyId, Long contactId, Collection<Kind> kinds);
}
