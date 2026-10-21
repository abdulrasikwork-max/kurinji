package com.safesphere.backend.intelligence.repository;

import com.safesphere.backend.intelligence.entity.AreaPattern;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Pattern memory (Phase 8C). Holds no owner and no link to signals; reads are by area cell only. */
public interface AreaPatternRepository extends JpaRepository<AreaPattern, AreaPattern.Key> {

    List<AreaPattern> findByKeyAreaCellIn(Collection<String> areaCells);

    @Modifying
    @Query("delete from AreaPattern p where p.key.areaCell = :areaCell and p.key.signalGroup = :signalGroup")
    int forget(@Param("areaCell") String areaCell, @Param("signalGroup") String signalGroup);
}
