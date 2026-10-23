package com.resqroute.dto;

/**
 * Body of POST /route/best-ambulance.
 *
 * sourceLatitude/sourceLongitude come automatically from the
 * victim's browser GPS — the victim never types coordinates.
 * severity is optional: CRITICAL or NORMAL (default NORMAL).
 */
public class EmergencyRequest {

    private Double sourceLatitude;
    private Double sourceLongitude;

    private String severity;

    public Double getSourceLatitude() {
        return sourceLatitude;
    }

    public void setSourceLatitude(Double sourceLatitude) {
        this.sourceLatitude = sourceLatitude;
    }

    public Double getSourceLongitude() {
        return sourceLongitude;
    }

    public void setSourceLongitude(Double sourceLongitude) {
        this.sourceLongitude = sourceLongitude;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }
}
