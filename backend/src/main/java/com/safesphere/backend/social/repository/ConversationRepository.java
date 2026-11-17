package com.safesphere.backend.social.repository;

import com.safesphere.backend.social.entity.Conversation;
import com.safesphere.backend.social.entity.ResponseEnums.ConversationKind;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Phase N5: plans and private replies under moments. */
public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    Optional<Conversation> findByPublicId(String publicId);

    /** Locks one conversation for a message, so turns are decided one after the other. */
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Conversation c WHERE c.publicId = :publicId")
    Optional<Conversation> lockByPublicId(@Param("publicId") String publicId);

    Optional<Conversation> findByMomentIdAndKind(long momentId, ConversationKind kind);

    Optional<Conversation> findByMomentIdAndReplierId(long momentId, long replierId);

    List<Conversation> findByMomentIdIn(Collection<Long> momentIds);

    /** The private replies [replierId] started (their side of Replies). */
    List<Conversation> findByReplierId(long replierId);

    /** Phase N5 (Replies): the private replies [userId] takes part in, as replier or as the moment's author. */
    @Query("SELECT c FROM Conversation c WHERE c.kind = com.safesphere.backend.social.entity.ResponseEnums"
            + ".ConversationKind.REPLY AND (c.replierId = :u OR c.momentId IN"
            + " (SELECT m.id FROM Moment m WHERE m.authorId = :u))")
    List<Conversation> repliesOf(@Param("u") long userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM Conversation c WHERE c.momentId IN :momentIds")
    int deleteByMomentIdIn(@Param("momentIds") Collection<Long> momentIds);
}
