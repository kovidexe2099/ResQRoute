package com.resqroute.controller;

import com.resqroute.dto.GpsUpdateRequest;
import com.resqroute.dto.StatusUpdateRequest;
import com.resqroute.model.Ambulance;
import com.resqroute.service.AmbulanceService;
import com.resqroute.service.EmergencyService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Fleet endpoints.
 *
 *   GET    /ambulance                  -> all ambulances
 *   GET    /ambulance/{id}            -> one ambulance
 *   POST   /ambulance                  -> add
 *   PUT    /ambulance/{id}            -> full update (admin)
 *   PUT    /ambulance/{id}/status     -> lifecycle status only
 *   PUT    /ambulance/{id}/location    -> live GPS fix
 *   DELETE /ambulance/{id}            -> delete (blocked while ACTIVE)
 *
 * Whenever an operation leaves an ambulance AVAILABLE, the pending
 * emergency queue is processed AFTER that operation's transaction
 * has committed — a queue failure can never roll back the fleet
 * operation itself. The same happens after a GPS fix, because the
 * first fix can make a previously unusable unit dispatchable.
 */
@RestController
@RequestMapping("/ambulance")
public class AmbulanceController {

    private final AmbulanceService ambulanceService;
    private final EmergencyService emergencyService;

    public AmbulanceController(AmbulanceService ambulanceService,
                               EmergencyService emergencyService) {
        this.ambulanceService = ambulanceService;
        this.emergencyService = emergencyService;
    }

    /**
     * Gives QUEUED emergencies a chance after an operation may have
     * produced a dispatchable (AVAILABLE + valid GPS) ambulance.
     */
    private void processQueueIfFreed(Ambulance ambulance) {
        if (ambulance != null
                && Ambulance.STATUS_AVAILABLE.equalsIgnoreCase(
                        ambulance.getStatus())
                && ambulance.hasValidGps()) {
            emergencyService.dispatchPendingEmergencies();
        }
    }

    @GetMapping
    public List<Ambulance> getAllAmbulances() {
        return ambulanceService.findAll();
    }

    @GetMapping("/{id}")
    public Ambulance getAmbulanceById(@PathVariable Long id) {
        return ambulanceService.findById(id);
    }

    @PostMapping
    public Ambulance addAmbulance(@RequestBody Ambulance ambulance) {
        Ambulance saved = ambulanceService.addAmbulance(ambulance);

        // a new AVAILABLE unit can immediately serve queued victims
        processQueueIfFreed(saved);

        return saved;
    }

    @PutMapping("/{id}")
    public Ambulance updateAmbulance(@PathVariable Long id,
                                     @RequestBody Ambulance update) {
        Ambulance updated = ambulanceService.updateAmbulance(id, update);

        processQueueIfFreed(updated);

        return updated;
    }

    @PutMapping("/{id}/status")
    public Ambulance updateStatus(@PathVariable Long id,
                                  @RequestBody StatusUpdateRequest request) {
        Ambulance updated = ambulanceService.updateStatus(id, request.getStatus());

        // the ambulance just became free -> queued emergencies
        // may be dispatched in their own transactions
        processQueueIfFreed(updated);

        return updated;
    }

    @PutMapping("/{id}/location")
    public Ambulance updateLocation(@PathVariable Long id,
                                    @RequestBody GpsUpdateRequest request) {
        Ambulance updated = ambulanceService.updateLocation(
                id,
                request.getLatitude(),
                request.getLongitude(),
                request.getLocationTimestamp()
        );

        // the first valid GPS fix can turn an unusable unit into a
        // dispatchable one -> queued emergencies get their chance
        processQueueIfFreed(updated);

        return updated;
    }

    @DeleteMapping("/{id}")
    public java.util.Map<String, Object> deleteAmbulance(@PathVariable Long id) {
        ambulanceService.deleteAmbulance(id);
        return java.util.Map.of(
                "message", "Ambulance deleted successfully with id " + id);
    }
}
