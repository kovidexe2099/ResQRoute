package com.resqroute.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests of the traffic model.
 *
 * These prove the viva statement:
 *   "Traffic changes the effective cost of the graph edges, and
 *    Dijkstra uses those costs to determine the shortest route."
 */
class TrafficServiceTests {

    // Hyderabad center used by the default configuration
    private static final double CENTER_LAT = 17.3850;
    private static final double CENTER_LON = 78.4867;

    private final TrafficService traffic =
            new TrafficService(CENTER_LAT, CENTER_LON, 4.0, 9.0);

    @Test
    void multipliersAreLowMediumHigh() {
        assertEquals(1.0, traffic.trafficMultiplier("LOW"));
        assertEquals(1.5, traffic.trafficMultiplier("MEDIUM"));
        assertEquals(2.0, traffic.trafficMultiplier("HIGH"));
        assertEquals(1.0, traffic.trafficMultiplier("whatever"));   // unknown -> LOW
        assertEquals(1.0, traffic.trafficMultiplier(null));
    }

    @Test
    void zonesAreRadialAroundTheCityCenter() {
        // at the center -> HIGH
        assertEquals(TrafficService.LEVEL_HIGH,
                traffic.trafficLevelAt(CENTER_LAT, CENTER_LON));

        // ~2.2 km north (inside 4 km core) -> HIGH
        assertEquals(TrafficService.LEVEL_HIGH,
                traffic.trafficLevelAt(CENTER_LAT + 0.02, CENTER_LON));

        // ~7.2 km north (between 4 and 9 km) -> MEDIUM
        assertEquals(TrafficService.LEVEL_MEDIUM,
                traffic.trafficLevelAt(CENTER_LAT + 0.065, CENTER_LON));

        // ~16.6 km north (outside 9 km) -> LOW
        assertEquals(TrafficService.LEVEL_LOW,
                traffic.trafficLevelAt(CENTER_LAT + 0.15, CENTER_LON));
    }

    @Test
    void effectiveEdgeCostIsBaseKmTimesTrafficFactor() {
        // a 1 km edge INSIDE the core (both ends at the center)
        double coreEdge = traffic.effectiveEdgeCostKm(
                1.0, CENTER_LAT - 0.0045, CENTER_LON, CENTER_LAT + 0.0045, CENTER_LON);

        // a 1 km edge FAR OUTSIDE the city
        double outerEdge = traffic.effectiveEdgeCostKm(
                1.0, 17.70, 78.4867, 17.70 + 0.009, 78.4867);

        // same base length, different traffic zone:
        // core edge must cost exactly twice the low-traffic edge
        assertEquals(2.0, coreEdge, 1e-6);
        assertEquals(1.0, outerEdge, 1e-6);
    }

    @Test
    void durationAdjustmentAppliesTheMultiplier() {
        assertEquals(10.0, traffic.adjustDurationMinutes(10.0, "LOW"));
        assertEquals(15.0, traffic.adjustDurationMinutes(10.0, "MEDIUM"));
        assertEquals(20.0, traffic.adjustDurationMinutes(10.0, "HIGH"));
    }

    @Test
    void corridorTrafficLevelSamplesTheMidpoint() {
        // corridor from the center outwards: midpoint ~5 km -> MEDIUM
        assertEquals(TrafficService.LEVEL_MEDIUM,
                traffic.corridorTrafficLevel(
                        CENTER_LAT, CENTER_LON, CENTER_LAT + 0.09, CENTER_LON));

        // corridor entirely inside the core -> HIGH
        assertEquals(TrafficService.LEVEL_HIGH,
                traffic.corridorTrafficLevel(
                        CENTER_LAT - 0.01, CENTER_LON,
                        CENTER_LAT + 0.01, CENTER_LON));

        // corridor entirely far outside the city -> LOW
        assertEquals(TrafficService.LEVEL_LOW,
                traffic.corridorTrafficLevel(17.70, CENTER_LON, 17.71, CENTER_LON));
    }

    @Test
    void haversineIsReasonablyAccurate() {
        // ~11.1 km due north (0.1 degree of latitude)
        double km = traffic.haversineKm(17.3850, 78.4867, 17.4850, 78.4867);
        assertEquals(11.1, km, 0.3);
    }
}
