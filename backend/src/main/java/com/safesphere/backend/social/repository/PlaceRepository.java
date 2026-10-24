package com.safesphere.backend.social.repository;

import com.safesphere.backend.social.entity.Place;
import com.safesphere.backend.social.entity.PostEnums.City;
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

    /** Phase 13D (M17): every place of one area, merged ones too (moderators looking for duplicates). */
    List<Place> findByAreaCellOrderByNameAsc(String areaCell, Pageable page);

    /** Phase 13C (C12): place-name search, by the normalised name only. */
    List<Place> findByNormalizedNameContainingAndMergedIntoIsNullOrderByNameAsc(String part, Pageable page);

    List<Place> findByNormalizedNameContainingAndCityAndMergedIntoIsNullOrderByNameAsc(String part, City city,
                                                                                     Pageable page);
}
