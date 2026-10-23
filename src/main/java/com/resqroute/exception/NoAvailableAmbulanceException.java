package com.resqroute.exception;

/**
 * No ambulance is currently dispatchable for the emergency (none
 * AVAILABLE, or none with a usable GPS position). The emergency
 * itself stays PENDING (queued) -> HTTP 409.
 */
public class NoAvailableAmbulanceException extends RuntimeException {

    private static final String DEFAULT_MESSAGE =
            "No available ambulance found. The emergency is queued as PENDING "
                    + "and will be dispatched as soon as an ambulance becomes "
                    + "available.";

    private final Long emergencyId;

    public NoAvailableAmbulanceException(Long emergencyId) {
        this(DEFAULT_MESSAGE, emergencyId);
    }

    /** Lets the caller explain WHY nothing could be dispatched. */
    public NoAvailableAmbulanceException(String message, Long emergencyId) {
        super(message);
        this.emergencyId = emergencyId;
    }

    public Long getEmergencyId() {
        return emergencyId;
    }
}
