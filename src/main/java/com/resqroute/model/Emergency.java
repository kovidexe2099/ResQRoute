package com.resqroute.model;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * A victim's emergency request.
 *
 * Lifecycle:
 *   PENDING    -> created, waiting for an ambulance
 *   DISPATCHED  -> an ambulance has been reserved for it
 *   COMPLETED   -> the victim was delivered to the hospital
 */
@Entity
public class Emergency {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_DISPATCHED = "DISPATCHED";
    public static final String STATUS_COMPLETED = "COMPLETED";

    public static final String SEVERITY_CRITICAL = "CRITICAL";
    public static final String SEVERITY_HIGH = "HIGH";
    public static final String SEVERITY_NORMAL = "NORMAL";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** When the emergency was raised. */
    private Instant createdAt;

    /** Victim GPS position (obtained automatically by the browser). */
    private Double sourceLatitude;
    private Double sourceLongitude;

    /**
     * CRITICAL > HIGH > NORMAL — higher severity is offered the next
     * free ambulance first (see EmergencyService).
     */
    private String severity;

    /** PENDING / DISPATCHED / COMPLETED. */
    private String status;

    public Emergency() {
    }

    public Emergency(Double sourceLatitude,
                     Double sourceLongitude,
                     String severity,
                     Instant createdAt) {
        this.sourceLatitude = sourceLatitude;
        this.sourceLongitude = sourceLongitude;
        this.severity = severity;
        this.status = STATUS_PENDING;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
