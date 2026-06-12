package com.jpassbolt.api.exception;

import java.util.Map;

/**
 * Validation exception carrying a structured errors body, mirroring the PHP
 * CustomValidationException / ValidationException used by the share endpoints.
 *
 * <p>
 * Unlike {@link PassboltApiException} (whose GlobalExceptionHandler envelope
 * always renders {@code body: null}), this exception is caught locally by
 * ShareController so the 400 "updateError" response can expose the per-row
 * validation errors, e.g.
 * {@code {permissions: {0: {id: {exists: "The permission does not exist."}}}}}
 * or
 * {@code {permissions: {at_least_one_owner: "At least one owner permission must be provided."}}}.
 * </p>
 */
public class ShareValidationException extends RuntimeException {

    private final Map<String, Object> errors;

    public ShareValidationException(String message, Map<String, Object> errors) {
        super(message);
        this.errors = errors;
    }

    public Map<String, Object> getErrors() {
        return errors;
    }
}
