package com.jpassbolt.api.controller;

import com.jpassbolt.api.exception.PassboltApiException;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.UserRepository;
import com.jpassbolt.api.service.EmailNotificationSettingsService;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Organization email notification settings endpoints — port of the PHP CE
 * plugin {@code Passbolt\EmailNotificationSettings} controllers
 * {@code NotificationOrgSettingsGetController} /
 * {@code NotificationOrgSettingsPostController}.
 *
 * <ul>
 *   <li>{@code GET /settings/emails/notifications.json} — the effective
 *       organization notification settings (DB row merged over defaults),
 *       flattened to snake_case booleans.</li>
 *   <li>{@code POST /settings/emails/notifications.json} — validate + merge the
 *       posted flat toggle map over the current settings + persist, returning
 *       the updated flattened settings.</li>
 * </ul>
 *
 * <p>
 * Both endpoints are <b>admin-only</b> (PHP {@code $this->User->role() !==
 * Role::ADMIN} on BOTH the GET and POST). The admin gate is enforced
 * in-controller via {@code userService.isAdmin(getCurrentUserId())}, mirroring
 * {@link MetadataSettingsController#requireAdmin}: a non-admin gets a 403 whose
 * {@code body} is an EMPTY STRING (not {@code null}) for spec-envelope
 * compatibility. The settings themselves are stored as a single
 * {@code organization_settings} row (property = {@code emailNotification}) by
 * {@link EmailNotificationSettingsService}; they are NOT entity-backed.
 * </p>
 *
 * <p>
 * Not part of the OpenAPI contract (plugin-redoc-0.yaml has no
 * {@code /settings/emails/notifications} path — the CE EmailNotificationSettings
 * plugin route is outside the documented OpenAPI domain), so the response is the
 * standard {@link ApiResponse} envelope but no {@code openApi().isValid(...)}
 * assertion runs against it (same documented situation as the locale/theme
 * endpoints).
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/settings")
@RequiredArgsConstructor
public class EmailNotificationSettingsController {

    private final EmailNotificationSettingsService emailNotificationSettingsService;
    private final UserService userService;
    private final UserRepository userRepository;

    /**
     * GET /settings/emails/notifications.json — the effective organization
     * notification settings, flattened to snake_case booleans (PHP
     * {@code NotificationOrgSettingsGetController::get}). Admin only.
     */
    @GetMapping({ "/emails/notifications", "/emails/notifications.json" })
    public ResponseEntity<Map<String, Object>> get() {
        String url = "/settings/emails/notifications.json";

        ResponseEntity<Map<String, Object>> adminGuard = requireAdmin(url);
        if (adminGuard != null) {
            return adminGuard;
        }

        Map<String, Object> settings = emailNotificationSettingsService.get();
        return ResponseEntity.ok(ApiResponse.success(
                "The operation was successful.", settings, url));
    }

    /**
     * POST /settings/emails/notifications.json — validate + merge the posted
     * flat toggle map over the current settings + persist, returning the
     * updated flattened settings (PHP
     * {@code NotificationOrgSettingsPostController::post}). Admin only.
     */
    @PostMapping({ "/emails/notifications", "/emails/notifications.json" })
    public ResponseEntity<Map<String, Object>> post(
            @RequestBody(required = false) Map<String, Object> request) {
        String url = "/settings/emails/notifications.json";
        String userId = getCurrentUserId();

        ResponseEntity<Map<String, Object>> adminGuard = requireAdmin(url);
        if (adminGuard != null) {
            return adminGuard;
        }

        Map<String, Object> settings = emailNotificationSettingsService.save(request, userId);
        return ResponseEntity.ok(ApiResponse.success(
                "The notification settings for the organization were updated.", settings, url));
    }

    // ---------------------------------------------------------------------
    // security helper (same pattern as MetadataSettingsController)
    // ---------------------------------------------------------------------

    /**
     * Admin gate (PHP {@code $this->User->role() !== Role::ADMIN} →
     * {@code ForbiddenException}). Returns a 403 response entity when the
     * current user is not an admin, otherwise null.
     *
     * <p>
     * The body is an EMPTY STRING (not {@code null}) so the envelope matches the
     * spec's admin-restricted response shape, mirroring
     * {@link MetadataSettingsController#requireAdmin} for cross-controller
     * consistency among the admin-gated settings endpoints.
     * </p>
     */
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
