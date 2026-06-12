package com.jpassbolt.api.controller;

import com.jpassbolt.api.exception.PassboltApiException;
import com.jpassbolt.api.service.SettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Server settings endpoint — port of the PHP
 * {@code SettingsIndexController::index()}.
 *
 * <p>
 * This is a PUBLIC endpoint ({@code allowUnauthenticated} in PHP): it is the
 * very first request the official browser extension fires on startup, usually
 * before login. Anonymous callers are legitimate and receive a reduced
 * (guest) view; authenticated callers receive the full view. This controller
 * therefore deliberately has no {@code getCurrentUserId()} — it must never
 * answer 401. Invalid or expired Bearer tokens silently degrade to the guest
 * view (the JwtAuthenticationFilter swallows token errors).
 * </p>
 *
 * <p>Note on mappings: no class-level @RequestMapping — with Boot 3's
 * PathPatternParser, "/settings" combined with ".json" yields
 * "/settings/.json" (NOT "/settings.json"), so full method-level paths
 * are used instead.</p>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class SettingsController {

    private final SettingsService settingsService;

    /**
     * GET /settings.json (and /settings)
     *
     * <p>
     * Supports the whitelisted query parameter {@code contain[header]}: when
     * {@code 0} or {@code false}, the response omits the {header, body}
     * envelope and the settings map is returned at the top level (PHP
     * {@code $withHeader == false} branch). Any other {@code contain[...]}
     * key yields 400 "Invalid contain." like PHP's QueryStringComponent.
     * </p>
     */
    @GetMapping({ "/settings", "/settings.json" })
    public ResponseEntity<Map<String, Object>> index(
            @RequestParam(name = "contain[header]", required = false) String containHeader,
            @RequestParam Map<String, String> allParams) {

        // QueryString whitelist check: only contain[header] is accepted.
        for (String paramName : allParams.keySet()) {
            if (paramName.startsWith("contain[") && !"contain[header]".equals(paramName)) {
                throw new PassboltApiException(HttpStatus.BAD_REQUEST, "Invalid contain.");
            }
        }

        boolean authenticated = isAuthenticated();
        Map<String, Object> settings = settingsService.getSettings(authenticated);

        // PHP QueryStringComponent normalizes '0'/'false' to false.
        boolean withHeader = !("0".equals(containHeader) || "false".equals(containHeader));
        if (!withHeader) {
            // No envelope: settings keys at the top level.
            return ResponseEntity.ok(settings);
        }

        return ResponseEntity.ok(createResponse("success", "The operation was successful.",
                settings, "/settings.json"));
    }

    /**
     * Guest detection. On permitAll paths an anonymous request still carries
     * an {@link AnonymousAuthenticationToken} (never a null context), so all
     * three conditions are required to avoid leaking the authenticated view
     * (version number, capability list) to unauthenticated callers.
     */
    private boolean isAuthenticated() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated()
                && !(auth instanceof AnonymousAuthenticationToken);
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
