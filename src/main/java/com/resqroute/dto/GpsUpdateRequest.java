package com.resqroute.dto;

/**
 * Body of PUT /ambulance/{id}/location — a live GPS fix.
 *
 * locationTimestamp is the client epoch milliseconds of the fix.
 * The backend uses it to reject stale (out-of-order) updates.
 * It is optional: older clients that send only coordinates are
 * treated as "the fix was taken now".
 */
public class GpsUpdateRequest {

    private Double latitude;
    private Double longitude;
    private Long locationTimestamp;

    public Double getLatitude() {
        return latitude;
    }

    public void setLatitude(Double latitude) {
        this.latitude = latitude;
    }

    public Double getLongitude() {
        return longitude;
    }

    public void setLongitude(Double longitude) {
        this.longitude = longitude;
    }

    public Long getLocationTimestamp() {
        return locationTimestamp;
    }

    public void setLocationTimestamp(Long locationTimestamp) {
        this.locationTimestamp = locationTimestamp;
    }
}
