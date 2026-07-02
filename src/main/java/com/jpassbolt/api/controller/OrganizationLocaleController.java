package com.jpassbolt.api.controller;

import com.jpassbolt.api.exception.PassboltApiException;
import com.jpassbolt.api.model.OrganizationSetting;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.UserRepository;
import com.jpassbolt.api.service.AccountLocaleService;
import com.jpassbolt.api.service.UserService;
import com.jpassbolt.api.util.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Organization default locale endpoint — port of the PHP Locale plugin
 * {@code OrganizationLocalesSelectController} (routes.php: POST
 * {@code /locale/settings.json}).
 *
 * <p>
 * <b>Admin-only</b> (PHP {@code assertIsAdmin}), enforced in-controller via
 * {@code userService.isAdmin(...)} like {@link SmtpSettingsController}: a
 * non-admin gets a 403 whose {@code body} is an empty string, with the
 * {@code assertIsAdmin} message "Access restricted to administrators."
 * (UserComponent default — the SMTP-family wording differs). The endpoint
 * stays behind {@code anyRequest().authenticated()} in SecurityConfig (NOT
 * whitelisted), so an anonymous call is a 401 from the security chain.
 * </p>
 *
 * <p>
 * The write goes to organization_settings(property='locale') — the very row
 * {@code SettingsService.getOrganizationLocale} / GET /settings.json
 * ({@code app.locale}) read back, so the loop is closed. An unsupported value
 * is a 400 "This is not a valid locale." like PHP.
 * </p>
 *
 * <p>
 * Not part of the OpenAPI contract (plugin-redoc-0.yaml declares no
 * {@code /locale/*} path), so no {@code openApi().isValid(...)} runs against
 * these responses — same documented situation as
 * {@link AccountLocaleController}. The envelope is still the standard
 * {@link ApiResponse} shape.
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/locale")
@RequiredArgsConstructor
public class OrganizationLocaleController {

    private final AccountLocaleService accountLocaleService;
    private final UserService userService;
    private final UserRepository userRepository;

    /**
     * POST /locale/settings.json — validate and persist the organization
     * locale ({@code {"value":"fr-FR"}}). Admin only. Mirrors PHP
     * {@code OrganizationLocalesSelectController::select}: the success message
     * is "The operation was successful." and the body is the saved setting.
     */
    @PostMapping({ "/settings", "/settings.json" })
    public ResponseEntity<Map<String, Object>> select(
            @RequestBody(required = false) AccountLocaleController.LocaleRequest request) {
        String url = "/locale/settings.json";
        String adminId = getCurrentUserId();

        if (!userService.isAdmin(adminId)) {
            return ResponseEntity.status(403).body(ApiResponse.withCode("error",
                    "Access restricted to administrators.", "", 403, url));
        }

        String value = request != null ? request.getValue() : null;
        OrganizationSetting setting = accountLocaleService.setOrganizationLocale(adminId, value);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", setting.getId());
        body.put("property_id", setting.getPropertyId());
        body.put("property", setting.getProperty());
        body.put("value", setting.getValue());
        body.put("created", setting.getCreated());
        body.put("modified", setting.getModified());
        body.put("created_by", setting.getCreatedBy());
        body.put("modified_by", setting.getModifiedBy());
        return ResponseEntity.ok(ApiResponse.success("The operation was successful.", body, url));
    }

    /**
     * Resolve the current authenticated user's id from the JWT principal
     * (same pattern as {@link SmtpSettingsController}).
     */
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
