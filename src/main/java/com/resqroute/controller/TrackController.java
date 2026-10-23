package com.resqroute.controller;

import com.resqroute.dto.TrackingResponse;
import com.resqroute.service.RouteService;
import org.springframework.web.bind.annotation.*;

/**
 * Private victim tracking — the ONLY victim-facing read endpoint.
 *
 *   GET /track/{token} -> this victim's own dispatch, authorized
 *                         solely by the dispatch's random tracking
 *                         token (never by an id).
 *
 * Public by design: the token itself is the authorization. It is
 * cryptographically secure and unique per dispatch, so a victim can
 * read their own dispatch and nothing else. The response contains no
 * internal ids and nothing about other emergencies or the fleet.
 */
@RestController
@RequestMapping("/track")
public class TrackController {

    private final RouteService routeService;

    public TrackController(RouteService routeService) {
        this.routeService = routeService;
    }

    @GetMapping("/{token}")
    public TrackingResponse trackByToken(@PathVariable String token) {
        return routeService.trackingByToken(token);
    }
}
