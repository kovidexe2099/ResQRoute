package com.resqroute.exception;

/**
 * The request payload is semantically invalid (unknown severity,
 * missing vehicle number / hospital name ...) -> HTTP 400.
 *
 * Distinct from InvalidCoordinatesException so clients receive a
 * truthful error code instead of "INVALID_COORDINATES" for
 * problems that are not GPS-related at all.
 */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }
}
