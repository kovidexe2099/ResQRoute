package com.resqroute.controller;

import com.resqroute.dto.ApiError;
import com.resqroute.exception.InvalidCoordinatesException;
import com.resqroute.exception.InvalidRequestException;
import com.resqroute.exception.InvalidStatusException;
import com.resqroute.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Central error handling: meaningful status codes + JSON bodies,
 * never a raw stack trace.
 *
 * Note: NO_AMBULANCE_AVAILABLE and NO_HOSPITALS (HTTP 409) are
 * answered by RouteController itself, because those responses also
 * carry the queued emergency's id and status for the frontend.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log =
            LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Semantically invalid payload (severity, vehicle number, ...). */
    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<ApiError> invalidRequest(
            InvalidRequestException exception) {
        return respond(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                exception.getMessage());
    }

    /** Malformed JSON body (e.g. a broken request) -> 400, not 500. */
    @ExceptionHandler(org.springframework.http.converter
            .HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> unreadableBody(
            org.springframework.http.converter
                    .HttpMessageNotReadableException exception) {
        return respond(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                "The request body is not valid JSON.");
    }

    @ExceptionHandler(InvalidCoordinatesException.class)
    public ResponseEntity<ApiError> invalidCoordinates(
            InvalidCoordinatesException exception) {
        return respond(HttpStatus.BAD_REQUEST, "INVALID_COORDINATES",
                exception.getMessage());
    }

    @ExceptionHandler(InvalidStatusException.class)
    public ResponseEntity<ApiError> invalidStatus(
            InvalidStatusException exception) {
        return respond(HttpStatus.CONFLICT, "INVALID_STATUS",
                exception.getMessage());
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> notFound(NotFoundException exception) {
        return respond(HttpStatus.NOT_FOUND, "NOT_FOUND",
                exception.getMessage());
    }

    /** Unknown paths (e.g. /track/ without a token) -> clean 404. */
    @ExceptionHandler(org.springframework.web.servlet.resource
            .NoResourceFoundException.class)
    public ResponseEntity<ApiError> noResource(
            org.springframework.web.servlet.resource
                    .NoResourceFoundException exception) {
        return respond(HttpStatus.NOT_FOUND, "NOT_FOUND",
                "The requested resource does not exist.");
    }

    /**
     * Wrong HTTP method for an endpoint (e.g. GET on
     * POST /route/best-ambulance) -> 405, not a generic 500.
     */
    @ExceptionHandler(org.springframework.web
            .HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> methodNotSupported(
            org.springframework.web
                    .HttpRequestMethodNotSupportedException exception) {
        return respond(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED",
                exception.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception exception) {
        log.error("Unhandled error: ", exception);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "Something went wrong on the server. Please try again.");
    }

    private ResponseEntity<ApiError> respond(HttpStatus status,
                                              String code,
                                              String message) {
        return respond(status, code, message, Map.of());
    }

    private ResponseEntity<ApiError> respond(HttpStatus status,
                                              String code,
                                              String message,
                                              Map<String, Object> details) {
        return ResponseEntity.status(status)
                .body(new ApiError(code, message, details));
    }
}
