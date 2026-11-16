package com.safesphere.backend.social.repository;

import com.safesphere.backend.social.entity.DailyQuestion;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Phase N4: the daily questions of a community (one per India calendar day). */
public interface DailyQuestionRepository extends JpaRepository<DailyQuestion, Long> {

    Optional<DailyQuestion> findByPublicId(String publicId);

    Optional<DailyQuestion> findByCommunityIdAndQuestionDay(Long communityId, LocalDate questionDay);

    List<DailyQuestion> findByCommunityIdAndQuestionDayGreaterThanEqualOrderByQuestionDayAsc(Long communityId,
                                                                                            LocalDate from);

    List<DailyQuestion> findByIdIn(Collection<Long> ids);
}
