package com.jpassbolt.api.controller;

import com.jpassbolt.api.dto.SelfRegistrationDto;
import com.jpassbolt.api.exception.PassboltApiException;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.UserRepository;
import com.jpassbolt.api.service.SelfRegistrationService;
import com.jpassbolt.api.service.UserService;
import com.jpassbolt.api.util.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Admin self-registration settings endpoints — port of the PHP CE plugin
 * {@code Passbolt\SelfRegistration} controllers
 * {@code SelfRegistrationGet/Set/DeleteSettingsController}.
 *
 * <ul>
 *   <li>{@code GET /self-registration/settings.json} — the current policy
 *       ({@code {provider, data:{allowed_domains}}} or {@code {provider:null,data:null}}).</li>
 *   <li>{@code POST /self-registration/settings.json} — validate + persist the
 *       policy, returning the rendered settings.</li>
 *   <li>{@code DELETE /self-registration/settings/{id}.json} — disable
 *       self-registration (delete the settings row).</li>
 * </ul>
 *
 * <p>All three are <b>admin-only</b> (PHP {@code assertIsAdmin}). The gate is
 * enforced in-controller via {@code userService.isAdmin(getCurrentUserId())},
 * mirroring {@link EmailNotificationSettingsController}: a non-admin gets a 403
 * whose {@code body} is an EMPTY STRING for envelope compatibility. The guest
 * dry-run + sign-up endpoints live in the separate, public
 * {@link SelfRegistrationController}.</p>
 *
 * <p>Not part of the OpenAPI contract (plugin-redoc-0.yaml has no
 * {@code /self-registration/*} path — only {@code passbolt.plugins.selfRegistration.enabled}
 * in the settings index and {@code registrationClosed} in healthcheck), so the
 * response is the standard {@link ApiResponse} envelope but no
 * {@code openApi().isValid(...)} assertion runs against it — the same documented
 * situation as the email-notification settings endpoints.</p>
 */
@Slf4j
@RestController
@RequestMapping("/self-registration")
@RequiredArgsConstructor
public class SelfRegistrationSettingsController {

    private final SelfRegistrationService selfRegistrationService;
    private final UserService userService;
    private final UserRepository userRepository;

    /** GET /self-registration/settings.json — the current policy. Admin only. */
    @GetMapping({ "/settings", "/settings.json" })
    public ResponseEntity<Map<String, Object>> get() {
        String url = "/self-registration/settings.json";

        ResponseEntity<Map<String, Object>> adminGuard = requireAdmin(url);
        if (adminGuard != null) {
            return adminGuard;
        }

        Map<String, Object> settings = selfRegistrationService.get();
        return ResponseEntity.ok(ApiResponse.success(
                "The operation was successful.", settings, url));
    }

    /** POST /self-registration/settings.json — validate + persist. Admin only. */
    @PostMapping({ "/settings", "/settings.json" })
    public ResponseEntity<Map<String, Object>> post(
            @RequestBody(required = false) SelfRegistrationDto.SettingsRequest request) {
        String url = "/self-registration/settings.json";
        String userId = getCurrentUserId();

        ResponseEntity<Map<String, Object>> adminGuard = requireAdmin(url);
        if (adminGuard != null) {
            return adminGuard;
        }

        Map<String, Object> settings = selfRegistrationService.save(request, userId);
        return ResponseEntity.ok(ApiResponse.success(
                "The self registration settings were updated.", settings, url));
    }

    /**
     * DELETE /self-registration/settings/{id}.json — disable self-registration.
     * Admin only.
     */
    @DeleteMapping({ "/settings/{id}", "/settings/{id}.json" })
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String id) {
        String url = "/self-registration/settings/" + id + ".json";
        String userId = getCurrentUserId();

        ResponseEntity<Map<String, Object>> adminGuard = requireAdmin(url);
        if (adminGuard != null) {
            return adminGuard;
        }

        Map<String, Object> settings = selfRegistrationService.delete(id, userId);
        return ResponseEntity.ok(ApiResponse.success(
                "The self registration settings were deleted.", settings, url));
    }

    // ---------------------------------------------------------------------
    // security helpers (same pattern as EmailNotificationSettingsController)
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
