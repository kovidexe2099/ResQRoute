package com.resqroute.exception;

/**
 * Coordinates missing or out of range -> HTTP 400.
 */
public class InvalidCoordinatesException extends RuntimeException {
    public InvalidCoordinatesException(String message) {
        super(message);
    }
}
