package com.jpassbolt.api.controller;

import com.jpassbolt.api.exception.PassboltApiException;
import com.jpassbolt.api.service.SetupService;
import com.jpassbolt.api.service.UserKeyPoliciesService;
import com.jpassbolt.api.util.ApiResponse;
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

import java.util.Map;
import java.util.regex.Pattern;

/**
 * User key policies endpoints — port of the PHP CE plugin
 * {@code Passbolt\UserKeyPolicies} {@code UserKeyPoliciesGetSettingsController}.
 * Its routes.php connects the same action twice:
 *
 * <ul>
 * <li>GET {@code /user-key-policies/settings.json} — authenticated users
 * only. Not in SecurityConfig's permitAll list, so it is covered by
 * {@code .anyRequest().authenticated()} and an anonymous call is rejected
 * with 401 by the security chain before this controller runs.</li>
 * <li>GET {@code /setup/user-key-policies/settings.json} — the setup/recover
 * form, reachable by guests carrying a {@code user_id} + register
 * {@code token} pair (PHP {@code allowUnauthenticated} +
 * {@code assertQueryParameters}). This is the only route the official
 * browser extension calls (userKeyPoliciesSettingsApiService, before
 * generating the user key pair). Covered by the {@code /setup/**} permitAll
 * entry; the JWT filter still runs there, so a signed-in caller is visible
 * and must NOT pass user_id/token (session confusion → 400, like PHP).</li>
 * </ul>
 *
 * <p>
 * Not part of the OpenAPI contract (plugin-redoc-0.yaml declares no
 * {@code /user-key-policies/*} path), so no {@code openApi().isValid(...)}
 * runs against these responses. The envelope is still the standard
 * {@link ApiResponse} shape.
 * </p>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class UserKeyPoliciesController {

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final UserKeyPoliciesService userKeyPoliciesService;
    private final SetupService setupService;

    /**
     * GET /user-key-policies/settings.json — the preferred user key
     * generation parameters (preferred_key_type / preferred_key_size /
     * preferred_key_curve / source).
     */
    @GetMapping({ "/user-key-policies/settings", "/user-key-policies/settings.json" })
    public ResponseEntity<Map<String, Object>> get() {
        String url = "/user-key-policies/settings.json";
        Map<String, Object> settings = userKeyPoliciesService.getSettings();
        return ResponseEntity.ok(ApiResponse.success(
                "The operation was successful.", settings, url));
    }

    /**
     * GET /setup/user-key-policies/settings.json — same payload, guest
     * access with a user_id + active register token (PHP
     * assertQueryParameters): 401 when a guest provides neither, 400 on a
     * non-UUID or unknown/expired token, 400 when a signed-in caller also
     * sends the guest credentials.
     */
    @GetMapping({ "/setup/user-key-policies/settings", "/setup/user-key-policies/settings.json" })
    public ResponseEntity<Map<String, Object>> getForSetup(
            @RequestParam(value = "user_id", required = false) String userId,
            @RequestParam(value = "token", required = false) String token) {
        String url = "/setup/user-key-policies/settings.json";
        assertQueryParameters(userId, token);
        Map<String, Object> settings = userKeyPoliciesService.getSettings();
        return ResponseEntity.ok(ApiResponse.success(
                "The operation was successful.", settings, url));
    }

    /**
     * Port of PHP UserKeyPoliciesGetSettingsController::assertQueryParameters.
     */
    private void assertQueryParameters(String userId, String token) {
        boolean hasUserToken = userId != null || token != null;

        if (isAuthenticated()) {
            if (hasUserToken) {
                throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                        "Conflicting authentication parameters, provide user_id/token"
                                + " only when the user is not already signed in.");
            }
            return;
        }

        if (userId == null || token == null) {
            throw new PassboltApiException(HttpStatus.UNAUTHORIZED,
                    "You are not authorized to access this location."
                            + " Sign-in to passbolt, or provide a valid user ID and authentication token.");
        }
        if (!UUID_PATTERN.matcher(userId).matches()) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "The user ID must be a valid UUID.");
        }
        if (!UUID_PATTERN.matcher(token).matches()) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "The authentication token must be a valid UUID.");
        }
        // Active + type register + owned by the user + not expired, 400
        // otherwise (PHP maps not-found/expired to BadRequest as well).
        setupService.getAndAssertRegisterToken(userId, token);
    }

    /**
     * True when a real principal is present (anonymous does not count).
     */
    private boolean isAuthenticated() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated()
                && !(auth instanceof AnonymousAuthenticationToken);
    }
}
