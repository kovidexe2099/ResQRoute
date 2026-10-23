package com.resqroute.service;

import com.resqroute.exception.InvalidCoordinatesException;
import com.resqroute.exception.InvalidRequestException;
import com.resqroute.exception.InvalidStatusException;
import com.resqroute.exception.NotFoundException;
import com.resqroute.model.Ambulance;
import com.resqroute.model.Dispatch;
import com.resqroute.repository.AmbulanceRepository;
import com.resqroute.repository.DispatchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * Ambulance fleet management: CRUD, live GPS ingestion and the
 * status lifecycle.
 *
 * GPS consistency rules:
 *   - coordinates are validated before they enter the database
 *   - updates carry a client timestamp; an OUT-OF-ORDER (stale)
 *     update is rejected, so a delayed fix can never overwrite a
 *     newer position
 *   - GPS jitter below 1 metre within 10 seconds is not written
 *     again (reduces pointless database writes from watchPosition)
 *
 * Lifecycle rules:
 *   - status values are validated against the defined lifecycle
 *   - practical transition guards on MANUAL status changes:
 *       . DISPATCHED can only ever be set by the atomic dispatch
 *         reservation (a hand-set DISPATCHED unit would be blocked
 *         with no dispatch behind it)
 *       . an OUT_OF_SERVICE unit can only return to AVAILABLE
 *       . a unit with an ACTIVE dispatch cannot be retired
 *         OUT_OF_SERVICE mid-mission (its emergency would be stuck)
 *   - when an ambulance becomes AVAILABLE again, its ACTIVE dispatch
 *     is completed and pending emergencies get a chance to be
 *     dispatched automatically
 */
@Service
public class AmbulanceService {

    private static final Logger log = LoggerFactory.getLogger(AmbulanceService.class);

    /** All statuses the backend accepts and understands. */
    public static final Set<String> VALID_STATUSES = Set.of(
            Ambulance.STATUS_AVAILABLE,
            Ambulance.STATUS_DISPATCHED,
            Ambulance.STATUS_TO_VICTIM,
            Ambulance.STATUS_AT_VICTIM,
            Ambulance.STATUS_TO_HOSPITAL,
            Ambulance.STATUS_OUT_OF_SERVICE
    );

    /** GPS updates closer than this (km) within this window are ignored. */
    private static final double NOISE_DISTANCE_KM = 0.001;   // 1 metre
    private static final long NOISE_WINDOW_MS = 10_000;      // 10 seconds

    private final AmbulanceRepository ambulanceRepository;
    private final DispatchRepository dispatchRepository;
    private final TrafficService trafficService;

    public AmbulanceService(AmbulanceRepository ambulanceRepository,
                            DispatchRepository dispatchRepository,
                            TrafficService trafficService) {
        this.ambulanceRepository = ambulanceRepository;
        this.dispatchRepository = dispatchRepository;
        this.trafficService = trafficService;
    }

    /* ------------------------------------------------------------------
       Queries
    ------------------------------------------------------------------ */

    public List<Ambulance> findAll() {
        return ambulanceRepository.findAll();
    }

    public Ambulance findById(Long id) {
        return ambulanceRepository.findById(id)
                .orElseThrow(() ->
                        new NotFoundException(
                                "Ambulance not found with id " + id));
    }

    /* ------------------------------------------------------------------
       CRUD
    ------------------------------------------------------------------ */

    @Transactional
    public Ambulance addAmbulance(Ambulance ambulance) {
        if (ambulance.getVehicleNumber() == null
                || ambulance.getVehicleNumber().isBlank()) {
            throw new InvalidRequestException(
                    "Vehicle number is required.");
        }

        ambulance.setStatus(normalizeStatus(ambulance.getStatus(),
                Ambulance.STATUS_AVAILABLE));

        validateGps(ambulance.getLatitude(), ambulance.getLongitude());

        Ambulance saved = ambulanceRepository.save(ambulance);
        log.info("Ambulance #{} ({}) added", saved.getId(),
                saved.getVehicleNumber());

        return saved;
    }

    @Transactional
    public Ambulance updateAmbulance(Long id, Ambulance update) {
        Ambulance ambulance = findById(id);

        if (update.getVehicleNumber() != null) {
            ambulance.setVehicleNumber(update.getVehicleNumber());
        }

        String newStatus = update.getStatus();

        if (newStatus != null) {
            newStatus = normalizeStatus(newStatus, null);
        }

        validateGps(update.getLatitude(), update.getLongitude());

        // only overwrite fields the request actually provided — a
        // form sending empty coordinates must not wipe the GPS
        if (update.getLocation() != null) {
            ambulance.setLocation(update.getLocation());
        }
        if (update.getLatitude() != null) {
            ambulance.setLatitude(update.getLatitude());
        }
        if (update.getLongitude() != null) {
            ambulance.setLongitude(update.getLongitude());
        }

        if (newStatus != null) {
            validateStatusTransition(ambulance, newStatus);
            ambulance.setStatus(newStatus);
        }

        Ambulance saved = ambulanceRepository.save(ambulance);

        // a full update may also set the status -> lifecycle handling
        if (newStatus != null) {
            handleStatusChange(saved, newStatus);
        }

        return ambulanceRepository.findById(id).orElse(saved);
    }

    @Transactional
    public void deleteAmbulance(Long id) {
        Ambulance ambulance = findById(id);

        // an ambulance with an ACTIVE emergency is untouchable
        dispatchRepository
                .findFirstByAmbulanceIdAndStatusOrderByIdDesc(
                        id, Dispatch.STATUS_ACTIVE)
                .ifPresent(dispatch -> {
                    throw new InvalidStatusException(
                            "Ambulance #" + id + " has an ACTIVE emergency (#"
                                    + dispatch.getEmergency().getId()
                                    + ") and cannot be deleted.");
                });

        // completed dispatches are the system's history: the foreign
        // key protects them, so the unit can be retired instead
        long history = dispatchRepository.countByAmbulanceId(id);
        if (history > 0) {
            throw new InvalidStatusException(
                    "Ambulance #" + id + " has " + history
                            + " dispatch record(s) and cannot be deleted. "
                            + "Set it to OUT_OF_SERVICE to retire it.");
        }

        ambulanceRepository.delete(ambulance);
        log.info("Ambulance #{} deleted", id);
    }

    /* ------------------------------------------------------------------
       Live GPS
    ------------------------------------------------------------------ */

    /**
     * Ingests a GPS position from an ambulance device.
     *
     * A live fix must carry BOTH coordinates: a half-empty update
     * would either crash the distance math or wipe the ambulance's
     * last known position, so missing values are rejected instead
     * of written.
     *
     * @param locationTimestamp client epoch milliseconds (optional;
     *                           missing -> treated as "now")
     */
    @Transactional
    public Ambulance updateLocation(Long id,
                                    Double latitude,
                                    Double longitude,
                                    Long locationTimestamp) {
        Ambulance ambulance = findById(id);

        if (latitude == null || longitude == null) {
            throw new InvalidCoordinatesException(
                    "A GPS update requires both latitude and longitude.");
        }

        validateGps(latitude, longitude);

        long incomingTimestamp =
                locationTimestamp != null ? locationTimestamp
                        : System.currentTimeMillis();

        Long storedTimestamp = ambulance.getLastLocationUpdate();

        // 1. STALE update: an out-of-order fix must not win
        if (storedTimestamp != null && incomingTimestamp < storedTimestamp) {
            log.info("Rejected STALE GPS update for ambulance #{} "
                            + "(incoming {} < stored {})",
                    id, incomingTimestamp, storedTimestamp);
            return ambulance;
        }

        // 2. NOISE: sub-metre jitter within 10 s -> skip the write
        if (storedTimestamp != null
                && ambulance.hasValidGps()
                && incomingTimestamp - storedTimestamp < NOISE_WINDOW_MS) {

            double movedKm = trafficService.haversineKm(
                    ambulance.getLatitude(), ambulance.getLongitude(),
                    latitude, longitude);

            if (movedKm < NOISE_DISTANCE_KM) {
                return ambulance;
            }
        }

        ambulance.setLatitude(latitude);
        ambulance.setLongitude(longitude);
        ambulance.setLastLocationUpdate(incomingTimestamp);

        return ambulanceRepository.save(ambulance);
    }

    /* ------------------------------------------------------------------
       Status lifecycle
    ------------------------------------------------------------------ */

    /**
     * PUT /ambulance/{id}/status — used by the dashboard while the
     * ambulance travels (TO_VICTIM -> AT_VICTIM -> TO_HOSPITAL ...).
     */
    @Transactional
    public Ambulance updateStatus(Long id, String status) {
        Ambulance ambulance = findById(id);

        String normalized = normalizeStatus(status, null);

        validateStatusTransition(ambulance, normalized);

        ambulance.setStatus(normalized);
        ambulanceRepository.save(ambulance);

        handleStatusChange(ambulance, normalized);

        return ambulanceRepository.findById(id).orElse(ambulance);
    }

    /**
     * Side effects of a status change:
     *   - becoming AVAILABLE completes the ACTIVE dispatch of this
     *     ambulance and closes the emergency.
     * (Processing the pending queue is triggered by the CONTROLLER
     * after this transaction commits, so queue failures can never
     * roll back a committed status change.)
     */
    private void handleStatusChange(Ambulance ambulance, String newStatus) {
        if (!Ambulance.STATUS_AVAILABLE.equals(newStatus)) {
            return;
        }

        dispatchRepository
                .findFirstByAmbulanceIdAndStatusOrderByIdDesc(
                        ambulance.getId(), Dispatch.STATUS_ACTIVE)
                .ifPresent(dispatch -> {
                    dispatch.setStatus(Dispatch.STATUS_COMPLETED);
                    dispatch.setCompletedAt(java.time.Instant.now());
                    dispatchRepository.save(dispatch);

                    dispatch.getEmergency()
                            .setStatus(com.resqroute.model.Emergency.STATUS_COMPLETED);
                    log.info("Dispatch #{} completed — ambulance #{} available again",
                            dispatch.getId(), ambulance.getId());
                });
    }

    /* ------------------------------------------------------------------
       Helpers
    ------------------------------------------------------------------ */

    /**
     * Practical guards for MANUAL status changes (updateStatus /
     * updateAmbulance). The automatic reservation path is not
     * affected: it only ever performs the atomic
     * AVAILABLE -> DISPATCHED. Everything else stays permissive so
     * the dashboard can freely report mission progress.
     */
    private void validateStatusTransition(Ambulance ambulance,
                                          String newStatus) {
        String current = ambulance.getStatus();

        if (newStatus.equalsIgnoreCase(current)) {
            return;   // re-sending the current status is a no-op
        }

        // DISPATCHED means "reserved by a dispatch": setting it by
        // hand would block the unit with no dispatch behind it
        if (Ambulance.STATUS_DISPATCHED.equals(newStatus)) {
            throw new InvalidStatusException(
                    "Status DISPATCHED is reserved for the automatic "
                            + "dispatch reservation and cannot be set by "
                            + "hand. Valid values: " + VALID_STATUSES);
        }

        // an off-duty unit has to return to AVAILABLE first
        if (Ambulance.STATUS_OUT_OF_SERVICE.equals(current)
                && !Ambulance.STATUS_AVAILABLE.equals(newStatus)) {
            throw new InvalidStatusException(
                    "Ambulance #" + ambulance.getId() + " is OUT_OF_SERVICE. "
                            + "Set it to AVAILABLE before giving it any "
                            + "other status.");
        }

        // retiring mid-mission would leave the emergency stuck in
        // DISPATCHED forever (mirrors the delete protection)
        if (Ambulance.STATUS_OUT_OF_SERVICE.equals(newStatus)
                && dispatchRepository
                        .findFirstByAmbulanceIdAndStatusOrderByIdDesc(
                                ambulance.getId(), Dispatch.STATUS_ACTIVE)
                        .isPresent()) {
            throw new InvalidStatusException(
                    "Ambulance #" + ambulance.getId() + " has an ACTIVE "
                            + "emergency. Set it to AVAILABLE after the "
                            + "delivery to retire it.");
        }
    }

    private String normalizeStatus(String status, String fallback) {
        if (status == null || status.isBlank()) {
            if (fallback == null) {
                throw new InvalidStatusException(
                        "Status is required. Valid values: " + VALID_STATUSES);
            }
            return fallback;
        }

        String upper = status.trim().toUpperCase();

        if (!VALID_STATUSES.contains(upper)) {
            throw new InvalidStatusException(
                    "Invalid status '" + status + "'. Valid values: "
                            + VALID_STATUSES);
        }

        return upper;
    }

    private void validateGps(Double latitude, Double longitude) {
        if (latitude == null && longitude == null) {
            return;   // no GPS in this request — allowed
        }

        if (latitude == null || longitude == null) {
            throw new InvalidCoordinatesException(
                    "Both latitude and longitude are required.");
        }

        if (latitude < -90 || latitude > 90
                || longitude < -180 || longitude > 180) {
            throw new InvalidCoordinatesException(
                    "GPS coordinates out of range: " + latitude + ", " + longitude);
        }
    }
}
