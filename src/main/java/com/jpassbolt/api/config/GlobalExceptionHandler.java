package com.jpassbolt.api.config;

import com.jpassbolt.api.exception.PassboltApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Global exception handler that catches {@link PassboltApiException} and
 * returns responses in Passbolt's standard JSON format.
 *
 * <pre>
 * {
 *   "header": { "id": "uuid", "status": "error", "code": 403, ... },
 *   "body": null
 * }
 * </pre>
 */
@Slf4j
@ControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Handle PassboltApiException with the appropriate HTTP status and
     * Passbolt-formatted JSON body.
     */
    @ExceptionHandler(PassboltApiException.class)
    public ResponseEntity<Map<String, Object>> handlePassboltApiException(PassboltApiException ex) {
        log.warn("API error [{}]: {}", ex.getStatus().value(), ex.getMessage());

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("id", UUID.randomUUID().toString());
        header.put("status", "error");
        header.put("servertime", System.currentTimeMillis() / 1000);
        header.put("code", ex.getStatus().value());
        header.put("message", ex.getMessage());
        header.put("url", "");

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("header", header);
        response.put("body", null);

        return ResponseEntity.status(ex.getStatus()).body(response);
    }
}
