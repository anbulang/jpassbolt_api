package com.jpassbolt.api.exception;

import org.springframework.http.HttpStatus;

/**
 * Custom exception for Passbolt API errors.
 * Used across all layers to signal recoverable business errors
 * with an appropriate HTTP status code and user-facing message.
 */
public class PassboltApiException extends RuntimeException {

    private final HttpStatus status;

    /**
     * Create a new PassboltApiException with an HTTP status and message.
     *
     * @param status  the HTTP status code to return
     * @param message the user-facing error message
     */
    public PassboltApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    /**
     * Create a new PassboltApiException with an HTTP status, message, and cause.
     *
     * @param status  the HTTP status code to return
     * @param message the user-facing error message
     * @param cause   the underlying cause
     */
    public PassboltApiException(HttpStatus status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    /**
     * Get the HTTP status code associated with this exception.
     *
     * @return the HTTP status
     */
    public HttpStatus getStatus() {
        return status;
    }
}
