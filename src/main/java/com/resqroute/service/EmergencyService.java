package com.resqroute.service;

import com.resqroute.dto.EmergencyRequest;
import com.resqroute.exception.InvalidCoordinatesException;
import com.resqroute.exception.InvalidRequestException;
import com.resqroute.exception.NoAvailableAmbulanceException;
import com.resqroute.model.Emergency;
import com.resqroute.repository.EmergencyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * Emergency lifecycle management.
 *
 * An emergency is ALWAYS persisted first (status PENDING) before the
 * dispatch attempt happens, so "no ambulance available" never
 * destroys the victim's request — it simply stays in the queue.
 *
 * Priority rule (simple and explainable):
 *   1. CRITICAL before HIGH before NORMAL emergencies
 *   2. within the same severity, the longest waiting first (FIFO)
 */
@Service
public class EmergencyService {

    private static final Logger log = LoggerFactory.getLogger(EmergencyService.class);

    private final EmergencyRepository emergencyRepository;
    private final RouteService routeService;

    public EmergencyService(EmergencyRepository emergencyRepository,
                            RouteService routeService) {
        this.emergencyRepository = emergencyRepository;
        this.routeService = routeService;
    }

    /**
     * Validates the request and persists a new PENDING emergency.
     * This is its own committed transaction: even if the dispatch
     * that follows fails, the emergency is not lost.
     */
    @Transactional
    public Emergency createEmergency(EmergencyRequest request) {
        Double latitude = request.getSourceLatitude();
        Double longitude = request.getSourceLongitude();

        if (latitude == null || longitude == null) {
            throw new InvalidCoordinatesException(
                    "Victim coordinates are required (latitude/longitude).");
        }

        if (latitude < -90 || latitude > 90
                || longitude < -180 || longitude > 180) {
            throw new InvalidCoordinatesException(
                    "Victim coordinates are out of range: "
                            + latitude + ", " + longitude);
        }

        String severity = normalizeSeverity(request.getSeverity());

        Emergency emergency = new Emergency(
                latitude, longitude, severity, Instant.now());

        Emergency saved = emergencyRepository.save(emergency);

        log.info("Emergency #{} created ({}, severity {})",
                saved.getId(), saved.getStatus(), severity);

        return saved;
    }

    /** Pending emergencies in priority order (see class comment). */
    public List<Emergency> pendingEmergenciesOrderedByPriority() {
        List<Emergency> pending = emergencyRepository
                .findByStatusOrderByCreatedAtAsc(Emergency.STATUS_PENDING);

        pending.sort(Comparator
                .comparingInt((Emergency e) ->
                        severityRank(e.getSeverity()))
                .thenComparing(Emergency::getCreatedAt));

        return pending;
    }

    /**
     * Tries to dispatch a FREE ambulance to every pending emergency,
     * in priority order. Called AFTER the transaction that freed an
     * ambulance has committed (from the controllers), so every
     * dispatchToEmergency below runs in its OWN transaction: a
     * failure rolls back only that one dispatch and never poisons
     * the caller's transaction.
     */
    public void dispatchPendingEmergencies() {
        List<Emergency> pending = pendingEmergenciesOrderedByPriority();

        if (pending.isEmpty()) {
            return;
        }

        for (Emergency emergency : pending) {
            // pre-check: never let the expected "no ambulance" case
            // become an exception crossing a transaction boundary
            if (routeService.countAvailableAmbulances() == 0) {
                break;   // fleet exhausted — remaining stay queued
            }

            try {
                routeService.dispatchToEmergency(emergency.getId());
                log.info("Pending emergency #{} dispatched automatically",
                        emergency.getId());
            } catch (NoAvailableAmbulanceException exception) {
                // lost a race for the last ambulance — stay queued
                break;
            } catch (Exception exception) {
                log.error("Could not dispatch pending emergency #{}: {}",
                        emergency.getId(), exception.getMessage());
            }
        }
    }

    /* ------------------------------------------------------------------
       Helpers
    ------------------------------------------------------------------ */

    private String normalizeSeverity(String severity) {
        if (severity == null) {
            return Emergency.SEVERITY_NORMAL;
        }

        String upper = severity.trim().toUpperCase();

        if (Emergency.SEVERITY_CRITICAL.equals(upper)
                || Emergency.SEVERITY_HIGH.equals(upper)
                || Emergency.SEVERITY_NORMAL.equals(upper)) {
            return upper;
        }

        throw new InvalidRequestException(
                "Severity must be CRITICAL, HIGH or NORMAL, got: " + severity);
    }

    private int severityRank(String severity) {
        if (Emergency.SEVERITY_CRITICAL.equalsIgnoreCase(severity)) {
            return 0;
        }
        if (Emergency.SEVERITY_HIGH.equalsIgnoreCase(severity)) {
            return 1;
        }
        return 2;
    }
}
