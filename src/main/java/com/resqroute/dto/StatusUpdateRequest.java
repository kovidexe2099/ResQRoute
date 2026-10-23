package com.resqroute.dto;

/**
 * Body of PUT /ambulance/{id}/status — a targeted, atomic status
 * change that cannot race with concurrent GPS location updates.
 */
public class StatusUpdateRequest {

    private String status;

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
