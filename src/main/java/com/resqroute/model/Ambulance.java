package com.resqroute.model;

import jakarta.persistence.*;

/**
 * An ambulance unit of the fleet.
 *
 * Status lifecycle:
 *   AVAILABLE      -> free, can be selected for an emergency
 *   DISPATCHED     -> just reserved for an emergency (backend owns this)
 *   TO_VICTIM      -> travelling towards the victim
 *   AT_VICTIM      -> reached the victim
 *   TO_HOSPITAL    -> travelling towards the hospital
 *   OUT_OF_SERVICE -> maintenance / off duty
 *
 * "Busy" is a legacy value from the first prototype; it is treated
 * as "not available" but is no longer written by the backend.
 */
@Entity
public class Ambulance {

    public static final String STATUS_AVAILABLE = "AVAILABLE";
    public static final String STATUS_DISPATCHED = "DISPATCHED";
    public static final String STATUS_TO_VICTIM = "TO_VICTIM";
    public static final String STATUS_AT_VICTIM = "AT_VICTIM";
    public static final String STATUS_TO_HOSPITAL = "TO_HOSPITAL";
    public static final String STATUS_OUT_OF_SERVICE = "OUT_OF_SERVICE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String vehicleNumber;

    private String status;

    /** Human readable base location (e.g. "Secunderabad"). */
    private String location;

    private Double latitude;
    private Double longitude;

    /**
     * Epoch milliseconds of the last accepted GPS update.
     * Used to reject stale (out-of-order) GPS fixes.
     */
    @Column(name = "last_location_update")
    private Long lastLocationUpdate;

    public Ambulance() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getVehicleNumber() {
        return vehicleNumber;
    }

    public void setVehicleNumber(String vehicleNumber) {
        this.vehicleNumber = vehicleNumber;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

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

    public Long getLastLocationUpdate() {
        return lastLocationUpdate;
    }

    public void setLastLocationUpdate(Long lastLocationUpdate) {
        this.lastLocationUpdate = lastLocationUpdate;
    }

    public boolean hasValidGps() {
        return latitude != null
                && longitude != null
                && latitude >= -90 && latitude <= 90
                && longitude >= -180 && longitude <= 180;
    }
}
