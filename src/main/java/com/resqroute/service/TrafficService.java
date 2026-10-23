package com.resqroute.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Traffic model of ResQRoute.
 *
 * NOTE: traffic levels are SIMULATED (they are computed from the
 * position relative to the city center), not fetched from a live
 * traffic feed. The rest of the system only talks to this service,
 * so a real traffic API can be plugged in later by replacing
 * trafficLevelAt() without touching any other class.
 *
 * Zone model (all values configurable in application.properties):
 *   - inside  cityCoreRadiusKm  of the center -> HIGH
 *   - inside  innerRingRadiusKm of the center -> MEDIUM
 *   - farther out                            -> LOW
 *
 * Traffic affects the system in two genuine ways:
 *   1. Graph edge weights:  effectiveCost = baseKm * trafficMultiplier
 *      (Dijkstra minimises the sum of these effective costs)
 *   2. ETA: duration minutes are multiplied by the same factor
 */
@Service
public class TrafficService {

    public static final String LEVEL_LOW = "LOW";
    public static final String LEVEL_MEDIUM = "MEDIUM";
    public static final String LEVEL_HIGH = "HIGH";

    private final double centerLatitude;
    private final double centerLongitude;
    private final double cityCoreRadiusKm;
    private final double innerRingRadiusKm;

    public TrafficService(
            @Value("${resqroute.traffic.center-latitude:17.3850}") double centerLatitude,
            @Value("${resqroute.traffic.center-longitude:78.4867}") double centerLongitude,
            @Value("${resqroute.traffic.city-core-radius-km:4.0}") double cityCoreRadiusKm,
            @Value("${resqroute.traffic.inner-ring-radius-km:9.0}") double innerRingRadiusKm) {
        this.centerLatitude = centerLatitude;
        this.centerLongitude = centerLongitude;
        this.cityCoreRadiusKm = cityCoreRadiusKm;
        this.innerRingRadiusKm = innerRingRadiusKm;
    }

    /** Simulated traffic multiplier per level (dimensionless). */
    public double trafficMultiplier(String trafficLevel) {
        String level = normalize(trafficLevel);

        if (LEVEL_HIGH.equals(level)) {
            return 2.0;
        }

        if (LEVEL_MEDIUM.equals(level)) {
            return 1.5;
        }

        return 1.0;
    }

    /** LOW / MEDIUM / HIGH for a GPS position (simulated zone model). */
    public String trafficLevelAt(double latitude, double longitude) {
        double distanceKm = haversineKm(
                latitude, longitude, centerLatitude, centerLongitude);

        if (distanceKm <= cityCoreRadiusKm) {
            return LEVEL_HIGH;
        }

        if (distanceKm <= innerRingRadiusKm) {
            return LEVEL_MEDIUM;
        }

        return LEVEL_LOW;
    }

    /**
     * Traffic level of a travel corridor (a graph edge or a whole
     * route leg), sampled at its midpoint. This is the ONLY place a
     * corridor midpoint is computed, so graph-edge traffic and
     * leg-ETA traffic can never disagree.
     */
    public String corridorTrafficLevel(double fromLatitude,
                                       double fromLongitude,
                                       double toLatitude,
                                       double toLongitude) {
        return trafficLevelAt(
                (fromLatitude + toLatitude) / 2.0,
                (fromLongitude + toLongitude) / 2.0);
    }

    /**
     * Traffic multiplier for a graph edge, sampled at the edge midpoint.
     * This is what makes traffic change the effective cost of edges.
     */
    public double edgeTrafficFactor(double fromLatitude,
                                    double fromLongitude,
                                    double toLatitude,
                                    double toLongitude) {
        return trafficMultiplier(corridorTrafficLevel(
                fromLatitude, fromLongitude, toLatitude, toLongitude));
    }

    /**
     * Effective graph cost of an edge:
     *   base edge cost (km) x traffic factor = traffic-adjusted cost.
     *
     * Units stay consistent: both factors are kilometres multiplied
     * by a dimensionless number, so Dijkstra minimises
     * "traffic-adjusted kilometres".
     */
    public double effectiveEdgeCostKm(double baseDistanceKm,
                                       double fromLatitude,
                                       double fromLongitude,
                                       double toLatitude,
                                       double toLongitude) {
        double trafficFactor =
                edgeTrafficFactor(fromLatitude, fromLongitude, toLatitude, toLongitude);

        return baseDistanceKm * trafficFactor;
    }

    /** Applies the traffic multiplier to a travel time in minutes. */
    public double adjustDurationMinutes(double durationMinutes,
                                        String trafficLevel) {
        return durationMinutes * trafficMultiplier(trafficLevel);
    }

    /** Normalizes any input to LOW / MEDIUM / HIGH (unknown -> LOW). */
    public String normalize(String trafficLevel) {
        if (trafficLevel == null) {
            return LEVEL_LOW;
        }

        String upper = trafficLevel.trim().toUpperCase();

        if (LEVEL_HIGH.equals(upper) || LEVEL_MEDIUM.equals(upper)) {
            return upper;
        }

        return LEVEL_LOW;
    }

    /** Haversine distance in kilometres. */
    public double haversineKm(double lat1, double lon1,
                              double lat2, double lon2) {
        final int earthRadiusKm = 6371;

        double deltaLat = Math.toRadians(lat2 - lat1);
        double deltaLon = Math.toRadians(lon2 - lon1);

        double a = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2)
                + Math.cos(Math.toRadians(lat1))
                * Math.cos(Math.toRadians(lat2))
                * Math.sin(deltaLon / 2)
                * Math.sin(deltaLon / 2);

        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        return earthRadiusKm * c;
    }
}
