package com.resqroute.dto;

import java.util.List;

/**
 * Response of GET /track/{token} — the PRIVATE victim tracking view.
 *
 * Security notes:
 *   - It is authorized ONLY by the dispatch's random tracking token
 *     (never by a sequential id), so a victim can see their own
 *     dispatch and nobody else's.
 *   - It deliberately contains NO internal ids (no emergencyId,
 *     ambulanceId, hospitalId or dispatchId) and nothing about any
 *     other emergency, ambulance or the rest of the database.
 *
 * Route semantics (same as DispatchResponse):
 *   ambulanceToVictim*  : leg 1, ambulance -> victim
 *   victimToHospital*   : leg 2, victim -> hospital
 *   geometry points follow OSRM GeoJSON order: [lon, lat]
 */
public record TrackingResponse(
        String dispatchStatus,
        String emergencyStatus,

        String ambulanceVehicleNumber,
        String ambulanceStatus,

        /** Live ambulance GPS (last accepted fix); null while unavailable. */
        Double ambulanceLatitude,
        Double ambulanceLongitude,

        /** False when the ambulance currently has no valid GPS position. */
        boolean ambulanceLocationAvailable,

        /** Victim position — used only to draw the "YOU" marker. */
        Double victimLatitude,
        Double victimLongitude,

        Double ambulanceToVictimDistanceKm,
        Double ambulanceToVictimDurationMin,
        Double victimToHospitalDistanceKm,
        Double victimToHospitalDurationMin,

        String trafficLevel,

        String hospitalName,
        String hospitalLocation,

        List<List<Double>> ambulanceToVictimRoute,
        List<List<Double>> victimToHospitalRoute
) {
}
