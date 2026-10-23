package com.resqroute.model;

import jakarta.persistence.*;

/**
 * A vertex of the ResQRoute routing graph.
 *
 * A LocationNode represents a landmark of the road network
 * (e.g. "Begumpet", "Tarnaka") with its GPS position.
 * The routing graph used by Dijkstra is built from these rows,
 * so the road-network model lives in the database and can be
 * edited without touching code.
 */
@Entity
public class LocationNode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    private Double latitude;
    private Double longitude;

    public LocationNode() {
    }

    public LocationNode(String name, double latitude, double longitude) {
        this.name = name;
        this.latitude = latitude;
        this.longitude = longitude;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
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

    /** True when this landmark can participate in routing. */
    public boolean hasValidCoordinates() {
        return latitude != null && longitude != null
                && latitude >= -90 && latitude <= 90
                && longitude >= -180 && longitude <= 180;
    }
}
