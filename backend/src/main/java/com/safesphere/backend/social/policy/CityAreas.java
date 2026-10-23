package com.safesphere.backend.social.policy;

import com.safesphere.backend.community.service.GeoCell;
import com.safesphere.backend.social.entity.PostEnums.City;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * Phase 13C (C6): which launch city a place belongs to, from its cell alone. Each city is an approximate bounding box
 * (latitude and longitude in degrees) around its urban area; the centre of the place's cell decides. Outside every box
 * a place is in no city (and so in no Edition). The boxes do not overlap. Changed only by code (and a backfill if one
 * is ever needed), never by people: the phone never sends a city for a place. Pure.
 */
public final class CityAreas {

    /** {latMin, latMax, lngMin, lngMax}. */
    private static final Map<City, double[]> BOXES = new EnumMap<>(City.class);

    static {
        BOXES.put(City.CHENNAI, new double[] {12.75, 13.35, 79.95, 80.35});
        BOXES.put(City.COIMBATORE, new double[] {10.85, 11.20, 76.80, 77.15});
        BOXES.put(City.MADURAI, new double[] {9.80, 10.05, 77.98, 78.25});
        BOXES.put(City.TIRUCHIRAPPALLI, new double[] {10.70, 10.92, 78.58, 78.82});
        BOXES.put(City.SALEM, new double[] {11.55, 11.78, 78.05, 78.25});
        BOXES.put(City.TIRUNELVELI, new double[] {8.62, 8.80, 77.62, 77.82});
        BOXES.put(City.VELLORE, new double[] {12.85, 13.00, 79.05, 79.22});
        BOXES.put(City.ERODE, new double[] {11.27, 11.42, 77.65, 77.80});
        BOXES.put(City.THOOTHUKUDI, new double[] {8.70, 8.85, 78.05, 78.20});
        BOXES.put(City.THANJAVUR, new double[] {10.72, 10.86, 79.05, 79.22});
        BOXES.put(City.PUDUCHERRY, new double[] {11.85, 12.05, 79.70, 79.90});
        BOXES.put(City.BENGALURU, new double[] {12.75, 13.20, 77.35, 77.85});
    }

    private CityAreas() {
    }

    /** The city whose box holds the centre of [cell] (any geohash length), or empty. */
    public static Optional<City> cityOf(String cell) {
        double[] centre = GeoCell.centre(cell);
        for (Map.Entry<City, double[]> entry : BOXES.entrySet()) {
            double[] box = entry.getValue();
            if (centre[0] >= box[0] && centre[0] <= box[1] && centre[1] >= box[2] && centre[1] <= box[3]) {
                return Optional.of(entry.getKey());
            }
        }
        return Optional.empty();
    }

    /** True for the cities an Edition can be made for (every listed city except OTHER). */
    public static boolean hasArea(City city) {
        return BOXES.containsKey(city);
    }
}
