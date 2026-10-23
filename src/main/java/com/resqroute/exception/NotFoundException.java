package com.resqroute.exception;

/**
 * Requested ambulance / hospital / emergency does not exist -> HTTP 404.
 */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
