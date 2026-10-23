package com.resqroute.controller;

import com.resqroute.dto.ApiError;
import com.resqroute.service.OsrmService;
import com.resqroute.exception.InvalidCoordinatesException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Small diagnostic endpoint for the OSRM integration
 * (staff-only, authenticated).
 *
 *   GET /osrm/test?fromLat=17.44&fromLon=78.46&toLat=17.42&toLon=78.47
 */
@RestController
@RequestMapping("/osrm")
public class OsrmController {

    private final OsrmService osrmService;

    public OsrmController(OsrmService osrmService) {
        this.osrmService = osrmService;
    }

    @GetMapping("/test")
    public ResponseEntity<?> testRoute(@RequestParam double fromLat,
                                       @RequestParam double fromLon,
                                       @RequestParam double toLat,
                                       @RequestParam double toLon) {

        if (fromLat < -90 || fromLat > 90
                || fromLon < -180 || fromLon > 180
                || toLat < -90 || toLat > 90
                || toLon < -180 || toLon > 180) {
            throw new InvalidCoordinatesException(
                    "Coordinates are out of range.");
        }

        return osrmService
                .getRoute(fromLat, fromLon, toLat, toLon)
                .<ResponseEntity<?>>map(route -> ResponseEntity.ok(Map.of(
                        "distanceKm", Math.round(route.distanceKm() * 100.0) / 100.0,
                        "durationMin",
                        Math.round(route.durationSeconds() / 60.0 * 100.0) / 100.0,
                        "points", route.coordinates().size()
                )))
                .orElseGet(() -> ResponseEntity.status(502).body(new ApiError(
                        "OSRM_UNAVAILABLE",
                        "OSRM did not return a usable route."
                )));
    }
}
