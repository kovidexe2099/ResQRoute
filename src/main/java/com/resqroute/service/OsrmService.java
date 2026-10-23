package com.resqroute.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Client for the public OSRM routing server.
 *
 * OSRM provides PRACTICAL road-network information:
 *   - real road geometry (polyline)
 *   - real road distance
 *   - real road duration
 *
 * OSRM is NOT ResQRoute's Dijkstra implementation. Dijkstra runs on
 * ResQRoute's own traffic-weighted graph (GraphService + DijkstraService)
 * and decides WHICH ambulance and WHICH hospital; OSRM then describes
 * the practical route for the chosen pair.
 *
 * The base URL is configurable (resqroute.osrm.base-url) so a
 * self-hosted OSRM server can be used instead of the public one.
 * Every failure (timeout, malformed response, no route) results in
 * an empty Optional — callers degrade gracefully instead of crashing.
 */
@Service
public class OsrmService {

    private static final Logger log = LoggerFactory.getLogger(OsrmService.class);

    /**
     * A practical road route returned by OSRM.
     */
    public record OsrmRoute(
            double distanceKm,
            double durationSeconds,
            List<List<Double>> coordinates   // OSRM GeoJSON order: [lon, lat]
    ) {
    }

    private final RestTemplate restTemplate;
    private final String osrmBaseUrl;

    public OsrmService(
            @Value("${resqroute.osrm.base-url:https://router.project-osrm.org}") String osrmBaseUrl) {
        this.osrmBaseUrl = osrmBaseUrl;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));

        this.restTemplate = new RestTemplate(factory);
    }

    /**
     * Fetches the practical road route between two GPS positions.
     * Returns empty when OSRM is unreachable or returns no usable route.
     */
    public Optional<OsrmRoute> getRoute(double startLatitude,
                                        double startLongitude,
                                        double endLatitude,
                                        double endLongitude) {
        String url = String.format(
                // Locale.US: %f must always produce "17.482500"-style
                // numbers — never comma decimals, which break the URL
                java.util.Locale.US,
                "%s/route/v1/driving/%f,%f;%f,%f?overview=full&geometries=geojson",
                osrmBaseUrl,
                startLongitude,
                startLatitude,
                endLongitude,
                endLatitude
        );

        Map<String, Object> response;

        try {
            response = restTemplate.getForObject(url, Map.class);
        } catch (RestClientException exception) {
            log.warn("OSRM request failed: {}", exception.getMessage());
            return Optional.empty();
        }

        return parseRoute(response);
    }

    /** Validates the OSRM response and extracts distance/duration/geometry. */
    @SuppressWarnings("unchecked")
    private Optional<OsrmRoute> parseRoute(Map<String, Object> response) {
        if (response == null) {
            log.warn("OSRM returned no response body");
            return Optional.empty();
        }

        Object routesValue = response.get("routes");

        if (!(routesValue instanceof List<?> routes) || routes.isEmpty()) {
            // OSRM signals errors like "NoRoute"/"TooManyRequests" in
            // the code field — log it so the failure is never silent
            log.warn("OSRM returned no route (code={})", response.get("code"));
            return Optional.empty();
        }

        if (!(routes.get(0) instanceof Map<?, ?> firstRoute)) {
            log.warn("OSRM route entry was not an object");
            return Optional.empty();
        }

        Object distanceValue = firstRoute.get("distance");
        Object durationValue = firstRoute.get("duration");

        if (!(distanceValue instanceof Number distance)
                || !(durationValue instanceof Number duration)) {
            log.warn("OSRM route had no usable distance/duration "
                            + "(distance={}, duration={})",
                    String.valueOf(distanceValue),
                    String.valueOf(durationValue));
            return Optional.empty();
        }

        if (distance.doubleValue() < 0 || duration.doubleValue() < 0) {
            log.warn("OSRM route had negative distance/duration");
            return Optional.empty();
        }

        if (!(firstRoute.get("geometry") instanceof Map<?, ?> geometry)) {
            log.warn("OSRM route had no geometry");
            return Optional.empty();
        }

        Object coordinatesValue = geometry.get("coordinates");

        if (!(coordinatesValue instanceof List<?> coordinates)
                || coordinates.size() < 2) {
            log.warn("OSRM geometry had too few points ({})",
                    coordinatesValue == null ? 0 : ((List<?>) coordinatesValue).size());
            return Optional.empty();
        }

        List<List<Double>> points = (List<List<Double>>) coordinates;

        for (List<Double> point : points) {
            if (point == null || point.size() < 2) {
                return Optional.empty();
            }
        }

        return Optional.of(new OsrmRoute(
                distance.doubleValue() / 1000.0,
                duration.doubleValue(),
                points
        ));
    }
}
