package com.resqroute.exception;

/**
 * No hospital with valid coordinates is registered -> HTTP 409.
 */
public class NoHospitalException extends RuntimeException {
    public NoHospitalException(String message) {
        super(message);
    }
}
