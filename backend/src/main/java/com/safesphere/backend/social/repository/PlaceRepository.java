package com.safesphere.backend.social.repository;

import com.safesphere.backend.social.entity.Place;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Phase 13B: places. Nothing here can find a place by a person: places record none (Q12). */
public interface PlaceRepository extends JpaRepository<Place, Long> {

    Optional<Place> findByPublicId(String publicId);

    Optional<Place> findByCellAndNormalizedName(String cell, String normalizedName);

    List<Place> findByAreaCellInAndMergedIntoIsNullOrderByNameAsc(Collection<String> areaCells, Pageable page);

    List<Place> findByIdIn(Collection<Long> ids);
}
