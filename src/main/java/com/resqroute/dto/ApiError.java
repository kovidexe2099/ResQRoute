package com.resqroute.dto;

import java.util.Map;

/**
 * Error body returned by the global exception handler.
 * Never contains a stack trace.
 */
public record ApiError(
        String code,
        String message,
        Map<String, Object> details
) {

    public ApiError(String code, String message) {
        this(code, message, Map.of());
    }
}
