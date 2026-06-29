package com.jpassbolt.api.controller;

import com.jpassbolt.api.dto.SmtpSettingsDto;
import com.jpassbolt.api.exception.PassboltApiException;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.UserRepository;
import com.jpassbolt.api.service.SmtpSettingsService;
import com.jpassbolt.api.service.UserService;
import com.jpassbolt.api.util.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Admin SMTP settings endpoints — port of the PHP CE plugin
 * {@code Passbolt\SmtpSettings} controllers {@code SmtpSettingsGet/Post/Email}.
 *
 * <ul>
 *   <li>{@code GET /smtp/settings.json} — the resolved SMTP config + {@code source}
 *       (db / env / undefined).</li>
 *   <li>{@code POST|PUT /smtp/settings.json} — validate + GPG-encrypt + persist,
 *       returning the freshly rendered settings.</li>
 *   <li>{@code POST /smtp/email.json} — send a test email through the posted config
 *       and return the SMTP {@code debug} trace (credentials masked).</li>
 * </ul>
 *
 * <p>All three are <b>admin-only</b> (PHP {@code assertIsAdmin}), enforced
 * in-controller via {@code userService.isAdmin(getCurrentUserId())} exactly like
 * {@link SelfRegistrationSettingsController} / {@link EmailNotificationSettingsController}:
 * a non-admin gets a 403 whose {@code body} is an EMPTY STRING for envelope
 * compatibility. The endpoints stay behind {@code anyRequest().authenticated()} in
 * {@link com.jpassbolt.api.config.SecurityConfig} (NOT whitelisted) — there is no
 * guest SMTP surface.</p>
 *
 * <p>Not part of the OpenAPI contract (plugin-redoc-0.yaml declares no
 * {@code /smtp/*} path — only the {@code healthcheck.smtpSettings} sub-schema and,
 * once advertised, {@code passbolt.plugins.smtpSettings.enabled} in the settings
 * index), so the standard {@link ApiResponse} envelope is returned but no
 * {@code openApi().isValid(...)} runs against these paths — the same documented
 * situation as the self-registration / email-notification settings endpoints.</p>
 */
@Slf4j
@RestController
@RequestMapping("/smtp")
@RequiredArgsConstructor
public class SmtpSettingsController {

    private final SmtpSettingsService smtpSettingsService;
    private final UserService userService;
    private final UserRepository userRepository;

    /** GET /smtp/settings.json — the resolved SMTP config. Admin only. */
    @GetMapping({ "/settings", "/settings.json" })
    public ResponseEntity<Map<String, Object>> get() {
        String url = "/smtp/settings.json";

        ResponseEntity<Map<String, Object>> adminGuard = requireAdmin(url);
        if (adminGuard != null) {
            return adminGuard;
        }

        Map<String, Object> settings = smtpSettingsService.get();
        return ResponseEntity.ok(ApiResponse.success(
                "The operation was successful.", settings, url));
    }

    /**
     * POST|PUT /smtp/settings.json — validate + persist. Admin only. PHP routes.php
     * registers BOTH verbs for the post action, so accept both.
     */
    @RequestMapping(value = { "/settings", "/settings.json" },
            method = { RequestMethod.POST, RequestMethod.PUT })
    public ResponseEntity<Map<String, Object>> post(
            @RequestBody(required = false) SmtpSettingsDto.SettingsRequest request) {
        String url = "/smtp/settings.json";
        String userId = getCurrentUserId();

        ResponseEntity<Map<String, Object>> adminGuard = requireAdmin(url);
        if (adminGuard != null) {
            return adminGuard;
        }

        try {
            Map<String, Object> settings = smtpSettingsService.save(request, userId);
            return ResponseEntity.ok(ApiResponse.success(
                    "The operation was successful.", settings, url));
        } catch (SmtpSettingsService.SmtpSettingsValidationException e) {
            return ResponseEntity.status(400).body(ApiResponse.error(e.getMessage(), e.getErrors(), url));
        }
    }

    /**
     * POST /smtp/email.json — send a test email through the posted config. Admin
     * only. On success returns {@code {debug}}; a validation failure is a 400 with
     * a field-error body, a delivery failure a 400 with the {@code {debug}} trace
     * (PHP SmtpSettingsEmailController error(...,400)).
     */
    @PostMapping({ "/email", "/email.json" })
    public ResponseEntity<Map<String, Object>> sendTestEmail(
            @RequestBody(required = false) SmtpSettingsDto.TestEmailRequest request) {
        String url = "/smtp/email.json";

        ResponseEntity<Map<String, Object>> adminGuard = requireAdmin(url);
        if (adminGuard != null) {
            return adminGuard;
        }

        try {
            Map<String, Object> body = smtpSettingsService.sendTestEmail(request);
            return ResponseEntity.ok(ApiResponse.success("The operation was successful.", body, url));
        } catch (SmtpSettingsService.SmtpSettingsValidationException e) {
            return ResponseEntity.status(400).body(ApiResponse.error(e.getMessage(), e.getErrors(), url));
        } catch (SmtpSettingsService.SmtpTestEmailException e) {
            return ResponseEntity.status(400).body(ApiResponse.error(e.getMessage(), e.getBody(), url));
        }
    }

    // ---------------------------------------------------------------------
    // security helpers (same pattern as SelfRegistrationSettingsController)
    // ---------------------------------------------------------------------

    private ResponseEntity<Map<String, Object>> requireAdmin(String url) {
        if (!userService.isAdmin(getCurrentUserId())) {
            return ResponseEntity.status(403).body(ApiResponse.withCode("error",
                    "You are not allowed to access this location.", "", 403, url));
        }
        return null;
    }

    private String getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            throw new PassboltApiException(HttpStatus.UNAUTHORIZED, "No authenticated user");
        }
        String username = auth.getName();
        return userRepository.findByUsername(username)
                .map(User::getId)
                .orElseThrow(() -> new PassboltApiException(HttpStatus.NOT_FOUND,
                        "User not found: " + username));
    }
}
