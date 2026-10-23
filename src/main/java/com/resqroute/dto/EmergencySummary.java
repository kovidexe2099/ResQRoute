package com.resqroute.dto;

import com.resqroute.model.Emergency;

import java.time.Instant;

/**
 * Compact view of an emergency (used for the pending queue).
 */
public record EmergencySummary(
        Long emergencyId,
        String status,
        String severity,
        Double sourceLatitude,
        Double sourceLongitude,
        Instant createdAt
) {

    public static EmergencySummary from(Emergency emergency) {
        return new EmergencySummary(
                emergency.getId(),
                emergency.getStatus(),
                emergency.getSeverity(),
                emergency.getSourceLatitude(),
                emergency.getSourceLongitude(),
                emergency.getCreatedAt()
        );
    }
}
