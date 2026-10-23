package com.resqroute.dto;

import java.time.Instant;
import java.util.List;

/**
 * API representation of a dispatch.
 *
 * Route semantics (fixed from the old RouteResponse):
 *   ambulanceToVictim*  : leg 1, ambulance -> victim
 *   victimToHospital*   : leg 2, victim -> hospital
 *
 * Route geometry follows OSRM GeoJSON order: each point is [lon, lat].
 */
public record DispatchResponse(
        Long dispatchId,
        String dispatchStatus,
        Instant createdAt,

        Long emergencyId,
        String emergencyStatus,
        String severity,
        Double sourceLatitude,
        Double sourceLongitude,

        Long ambulanceId,
        String vehicleNumber,
        String ambulanceStatus,
        String ambulanceLocation,

        /** Live ambulance GPS (last accepted fix) — for the map marker. */
        Double ambulanceLatitude,
        Double ambulanceLongitude,

        Long hospitalId,
        String hospitalName,
        String hospitalLocation,

        Double ambulanceToVictimDistanceKm,
        Double ambulanceToVictimDurationMin,
        Double victimToHospitalDistanceKm,
        Double victimToHospitalDurationMin,

        String trafficLevel,

        /** Private tracking token of this dispatch (for the victim). */
        String trackingToken,

        List<List<Double>> ambulanceToVictimRoute,
        List<List<Double>> victimToHospitalRoute
) {
}
