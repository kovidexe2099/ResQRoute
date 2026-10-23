package com.resqroute.exception;

/**
 * Invalid status value or invalid state transition -> HTTP 400/409.
 */
public class InvalidStatusException extends RuntimeException {
    public InvalidStatusException(String message) {
        super(message);
    }
}
