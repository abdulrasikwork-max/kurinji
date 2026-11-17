package com.safesphere.backend.social.repository;

import com.safesphere.backend.social.entity.Message;
import com.safesphere.backend.social.entity.ResponseEnums.MessageKind;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Phase N5: the messages of conversations. */
public interface MessageRepository extends JpaRepository<Message, Long> {

    Optional<Message> findByPublicId(String publicId);

    /** Locks one message (reports about it run one after the other). */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM Message m WHERE m.id = :id")
    Optional<Message> lockById(@Param("id") long id);

    /** A conversation's messages in order (oldest first). */
    List<Message> findByConversationIdOrderByCreatedAtAscIdAsc(long conversationId);

    /** The newest messages of a kind in a conversation (the turn rule reads the last TEXT one). */
    List<Message> findByConversationIdAndKindOrderByCreatedAtDescIdDesc(long conversationId, MessageKind kind,
                                                                         Pageable page);

    List<Message> findByConversationIdIn(Collection<Long> conversationIds);

    /** Up to [page] messages written before [message] in its conversation, newest first (a moderator's context). */
    @Query("SELECT m FROM Message m WHERE m.conversationId = :conversationId AND (m.createdAt < :at"
            + " OR (m.createdAt = :at AND m.id < :id)) ORDER BY m.createdAt DESC, m.id DESC")
    List<Message> before(@Param("conversationId") long conversationId, @Param("at") LocalDateTime at,
                         @Param("id") long id, Pageable page);

    boolean existsByConversationIdInAndInReviewTrue(Collection<Long> conversationIds);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM Message m WHERE m.conversationId IN :conversationIds")
    int deleteByConversationIdIn(@Param("conversationIds") Collection<Long> conversationIds);
}
