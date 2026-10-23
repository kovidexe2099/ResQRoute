package com.resqroute.controller;

import com.resqroute.dto.ApiError;
import com.resqroute.dto.DispatchResponse;
import com.resqroute.dto.EmergencyRequest;
import com.resqroute.exception.NoAvailableAmbulanceException;
import com.resqroute.exception.NoHospitalException;
import com.resqroute.model.Emergency;
import com.resqroute.service.EmergencyService;
import com.resqroute.service.RouteService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Dispatch + live dashboard endpoints.
 *
 *   POST /route/best-ambulance  -> create emergency + dispatch
 *   GET  /route/latest          -> dashboard snapshot (from the DB)
 */
@RestController
@RequestMapping("/route")
public class RouteController {

    private final EmergencyService emergencyService;
    private final RouteService routeService;

    public RouteController(EmergencyService emergencyService,
                           RouteService routeService) {
        this.emergencyService = emergencyService;
        this.routeService = routeService;
    }

    /**
     * Victim flow: the emergency is ALWAYS persisted first (PENDING),
     * then we try to dispatch it. 200 -> dispatched immediately.
     * 409 -> no ambulance free right now; the emergency stays queued
     * and will be dispatched automatically when one frees up.
     */
    @PostMapping("/best-ambulance")
    public ResponseEntity<?> dispatchBestAmbulance(
            @RequestBody EmergencyRequest request) {

        Emergency emergency = emergencyService.createEmergency(request);

        try {
            DispatchResponse dispatch =
                    routeService.dispatchToEmergency(emergency.getId());

            return ResponseEntity.ok(dispatch);

        } catch (NoAvailableAmbulanceException exception) {
            return ResponseEntity.status(409).body(new ApiError(
                    "NO_AMBULANCE_AVAILABLE",
                    exception.getMessage(),
                    java.util.Map.of(
                            "emergencyId", exception.getEmergencyId(),
                            "emergencyStatus", Emergency.STATUS_PENDING
                    )
            ));

        } catch (NoHospitalException exception) {
            return ResponseEntity.status(409).body(new ApiError(
                    "NO_HOSPITALS",
                    exception.getMessage(),
                    java.util.Map.of(
                            "emergencyId", emergency.getId(),
                            "emergencyStatus", Emergency.STATUS_PENDING
                    )
            ));
        }
    }

    /**
     * Everything the live dashboard polls for — read straight from
     * the database, so nothing is lost when the server restarts.
     */
    @GetMapping("/latest")
    public RouteService.DashboardSnapshot latestSnapshot() {
        return routeService.latestSnapshot();
    }

    /**
     * ALL currently active dispatches (staff-only). Every dispatch
     * carries its own ambulance's LIVE GPS position, so the Command
     * Center can render several simultaneous emergencies completely
     * independently.
     */
    @GetMapping("/active")
    public java.util.List<com.resqroute.dto.DispatchResponse> activeDispatches() {
        return routeService.activeDispatches();
    }
}
