package com.jpassbolt.api.config;

import com.jpassbolt.api.exception.PassboltApiException;
import com.jpassbolt.api.util.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.Map;

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

        // 迁移到共享信封工具：补 action(uuid) 等 spec required 字段，code 用真实 HTTP 状态码，
        // body 保持 JSON null，url 维持空串（既有偏差，未在本任务范围内修改）。
        Map<String, Object> response = ApiResponse.withExplicitAction("error", ex.getMessage(), null,
                ex.getStatus().value(), ApiResponse.actionFor(""), "");

        return ResponseEntity.status(ex.getStatus()).body(response);
    }
}
