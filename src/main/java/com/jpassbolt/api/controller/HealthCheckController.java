package com.jpassbolt.api.controller;

import com.jpassbolt.api.exception.PassboltApiException;
import com.jpassbolt.api.model.Role;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.RoleRepository;
import com.jpassbolt.api.repository.UserRepository;
import com.jpassbolt.api.service.HealthcheckService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Healthcheck endpoints compatible with the official Passbolt API.
 *
 * <ul>
 * <li>{@code GET/HEAD /healthcheck/status.json} — public liveness probe
 * (PHP {@code HealthcheckStatusController::status()}). No authentication,
 * body is the bare string {@code "OK"}.</li>
 * <li>{@code GET /healthcheck.json} — full healthcheck report, restricted to
 * administrators (PHP {@code HealthcheckIndexController::index()}). Can be
 * disabled via {@code jpassbolt.healthcheck.index-endpoint-enabled=false}
 * (mirrors {@code passbolt.plugins.healthcheck.security.indexEndpointEnabled},
 * checked before the admin check, like the PHP beforeFilter).</li>
 * </ul>
 *
 * <p>
 * Note: this controller intentionally has no class-level
 * {@code @RequestMapping("/healthcheck")}. Spring's
 * {@code PathPattern.combine("/healthcheck", ".json")} would produce
 * {@code "/healthcheck/.json"} (two path segments), which never matches the
 * real request {@code /healthcheck.json} — hence absolute method-level paths.
 * The old non-standard {@code GET /health-check} endpoint has been removed;
 * the matching {@code permitAll} entry must be dropped from SecurityConfig.
 * </p>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class HealthCheckController {

    private final HealthcheckService healthcheckService;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;

    @Value("${jpassbolt.healthcheck.index-endpoint-enabled:true}")
    private boolean indexEndpointEnabled;

    /**
     * GET/HEAD /healthcheck/status.json (and /healthcheck/status)
     * Public liveness probe — no authentication, no error branch.
     * Spring MVC automatically routes HEAD to this GET handler.
     *
     * <p>
     * Contract quirks (components/responses/status): the body is the bare
     * string {@code "OK"} (not an object) and {@code header.message} is
     * {@code "OK"} (not the standard success message) — mirrors PHP
     * {@code $this->success(__('OK'), 'OK')}.
     * </p>
     */
    @GetMapping({ "/healthcheck/status", "/healthcheck/status.json" })
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(createResponse("success", "OK", "OK", "/healthcheck/status.json"));
    }

    /**
     * GET /healthcheck.json (and /healthcheck)
     * Full healthcheck report. Only administrators can query this endpoint.
     * Check order mirrors PHP: endpoint-enabled switch first (beforeFilter),
     * then admin assertion ({@code UserComponent::assertIsAdmin()}).
     */
    @GetMapping({ "/healthcheck", "/healthcheck.json" })
    public ResponseEntity<Map<String, Object>> index() {
        if (!indexEndpointEnabled) {
            return ResponseEntity.status(403)
                    .body(createResponse("error", "Healthcheck security index endpoint disabled.",
                            null, "/healthcheck.json"));
        }

        if (!isCurrentUserAdmin()) {
            return ResponseEntity.status(403)
                    .body(createResponse("error", "Access restricted to administrators.",
                            null, "/healthcheck.json"));
        }

        Map<String, Object> report = healthcheckService.getHealthcheckReport();
        return ResponseEntity.ok(createResponse("success", "The operation was successful.",
                report, "/healthcheck.json"));
    }

    /**
     * Check whether the current authenticated user has the admin role.
     * The JWT principal only carries the username (authorities are empty),
     * so the role has to be resolved from the database and compared by
     * role name (never by hard-coded role UUID).
     */
    private boolean isCurrentUserAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            throw new PassboltApiException(HttpStatus.UNAUTHORIZED, "No authenticated user");
        }
        String username = auth.getName();
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new PassboltApiException(HttpStatus.NOT_FOUND,
                        "User not found: " + username));
        return roleRepository.findById(user.getRoleId())
                .map(role -> Role.ADMIN.equals(role.getName()))
                .orElse(false);
    }

    private Map<String, Object> createResponse(String status, String message, Object body, String url) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("header", Map.of(
                "id", java.util.UUID.randomUUID().toString(),
                "status", status,
                "servertime", System.currentTimeMillis() / 1000,
                "code", "success".equals(status) ? 200 : 400,
                "message", message,
                "url", url));
        response.put("body", body != null ? body : new LinkedHashMap<>());
        return response;
    }
}
