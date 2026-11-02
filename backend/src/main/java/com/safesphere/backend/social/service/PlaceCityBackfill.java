package com.safesphere.backend.social.service;

import com.safesphere.backend.social.entity.Place;
import com.safesphere.backend.social.policy.CityCatalog;
import com.safesphere.backend.social.repository.PlaceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Phase 17E (D-5, V28): places created while they were outside every launch-city box get the catalog city that now
 * covers them. Once per start, idempotent (only places with no city are looked at; a city is never changed), and a
 * failure never stops the server (a place without a city is simply in no Edition, as before). SQL cannot decode a
 * geohash, so this is Java and not part of V28.
 */
@Component
public class PlaceCityBackfill implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PlaceCityBackfill.class);

    private final PlaceRepository places;
    private final TransactionTemplate tx;

    public PlaceCityBackfill(PlaceRepository places, PlatformTransactionManager transactions) {
        this.places = places;
        this.tx = new TransactionTemplate(transactions);
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            Integer updated = tx.execute(status -> backfill());
            if (updated != null && updated > 0) log.info("Places given a city from the catalog: {}", updated);
        } catch (RuntimeException e) {
            log.warn("Place city backfill failed ({}); the places stay without a city", e.getClass().getSimpleName());
        }
    }

    /** One pass; answers how many places got a city. */
    public int backfill() {
        int updated = 0;
        for (Place place : places.findByCityIsNull()) {
            String city = CityCatalog.cityOf(place.getCell()).orElse(null);
            if (city != null) {
                place.placeInCity(city);
                places.save(place);
                updated++;
            }
        }
        return updated;
    }
}
