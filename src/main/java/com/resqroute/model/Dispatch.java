package com.resqroute.model;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * The assignment of one ambulance + one hospital to one emergency.
 *
 * Lifecycle:
 *   ACTIVE    -> the ambulance is working on this emergency
 *   COMPLETED -> the victim was delivered to the hospital
 *
 * The practical road routes (OSRM geometry) are stored as JSON text
 * so the dashboard can redraw the dispatch even after a restart.
 *
 * unique(emergency_id): an emergency can have AT MOST ONE dispatch —
 * the database itself guarantees a victim is never double-dispatched,
 * even if two transactions race.
 */
@Entity
@Table(uniqueConstraints = @UniqueConstraint(
        name = "uk_dispatch_emergency",
        columnNames = "emergency_id"
))
public class Dispatch {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_COMPLETED = "COMPLETED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Instant createdAt;

    /** When the ambulance became available again (dispatch finished). */
    private Instant completedAt;

    /** ACTIVE / COMPLETED. */
    private String status;

    @ManyToOne(optional = false)
    private Emergency emergency;

    @ManyToOne(optional = false)
    private Ambulance ambulance;

    @ManyToOne(optional = false)
    private Hospital hospital;

    /* ------------------------------------------------------------------
       Practical route data for leg 1: AMBULANCE -> VICTIM
       (provided by OSRM, or estimated when OSRM is unreachable)
    ------------------------------------------------------------------ */
    private Double ambulanceToVictimDistanceKm;
    private Double ambulanceToVictimDurationMin;

    /* ------------------------------------------------------------------
       Practical route data for leg 2: VICTIM -> HOSPITAL
    ------------------------------------------------------------------ */
    private Double victimToHospitalDistanceKm;
    private Double victimToHospitalDurationMin;

    /** LOW / MEDIUM / HIGH on the ambulance -> victim corridor. */
    private String trafficLevel;

    /**
     * Private tracking token handed to the victim of THIS dispatch.
     * A cryptographically secure random value — it is the ONLY way
     * this dispatch can be read from the public tracking page:
     * knowing an id is NOT enough. One token belongs to exactly one
     * victim's dispatch.
     */
    @Column(name = "tracking_token", unique = true)
    private String trackingToken;

    /** When the tracking token was issued (at dispatch creation). */
    @Column(name = "tracking_token_created_at")
    private Instant trackingTokenCreatedAt;

    /**
     * OSRM geometry of leg 1, stored as a JSON array of [lon, lat].
     * LONGVARCHAR maps to LONGTEXT on MySQL and CLOB on H2 — the
     * default @Lob mapping created TINYTEXT (255 bytes) on MySQL,
     * which truncated real road geometries.
     */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "ambulance_to_victim_route_json")
    private String ambulanceToVictimRouteJson;

    /** OSRM geometry of leg 2, stored as a JSON array of [lon, lat]. */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "victim_to_hospital_route_json")
    private String victimToHospitalRouteJson;

    public Dispatch() {
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

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Emergency getEmergency() {
        return emergency;
    }

    public void setEmergency(Emergency emergency) {
        this.emergency = emergency;
    }

    public Ambulance getAmbulance() {
        return ambulance;
    }

    public void setAmbulance(Ambulance ambulance) {
        this.ambulance = ambulance;
    }

    public Hospital getHospital() {
        return hospital;
    }

    public void setHospital(Hospital hospital) {
        this.hospital = hospital;
    }

    public Double getAmbulanceToVictimDistanceKm() {
        return ambulanceToVictimDistanceKm;
    }

    public void setAmbulanceToVictimDistanceKm(Double ambulanceToVictimDistanceKm) {
        this.ambulanceToVictimDistanceKm = ambulanceToVictimDistanceKm;
    }

    public Double getAmbulanceToVictimDurationMin() {
        return ambulanceToVictimDurationMin;
    }

    public void setAmbulanceToVictimDurationMin(Double ambulanceToVictimDurationMin) {
        this.ambulanceToVictimDurationMin = ambulanceToVictimDurationMin;
    }

    public Double getVictimToHospitalDistanceKm() {
        return victimToHospitalDistanceKm;
    }

    public void setVictimToHospitalDistanceKm(Double victimToHospitalDistanceKm) {
        this.victimToHospitalDistanceKm = victimToHospitalDistanceKm;
    }

    public Double getVictimToHospitalDurationMin() {
        return victimToHospitalDurationMin;
    }

    public void setVictimToHospitalDurationMin(Double victimToHospitalDurationMin) {
        this.victimToHospitalDurationMin = victimToHospitalDurationMin;
    }

    public String getTrafficLevel() {
        return trafficLevel;
    }

    public void setTrafficLevel(String trafficLevel) {
        this.trafficLevel = trafficLevel;
    }

    public String getTrackingToken() {
        return trackingToken;
    }

    public void setTrackingToken(String trackingToken) {
        this.trackingToken = trackingToken;
    }

    public Instant getTrackingTokenCreatedAt() {
        return trackingTokenCreatedAt;
    }

    public void setTrackingTokenCreatedAt(Instant trackingTokenCreatedAt) {
        this.trackingTokenCreatedAt = trackingTokenCreatedAt;
    }

    public String getAmbulanceToVictimRouteJson() {
        return ambulanceToVictimRouteJson;
    }

    public void setAmbulanceToVictimRouteJson(String ambulanceToVictimRouteJson) {
        this.ambulanceToVictimRouteJson = ambulanceToVictimRouteJson;
    }

    public String getVictimToHospitalRouteJson() {
        return victimToHospitalRouteJson;
    }

    public void setVictimToHospitalRouteJson(String victimToHospitalRouteJson) {
        this.victimToHospitalRouteJson = victimToHospitalRouteJson;
    }
}
