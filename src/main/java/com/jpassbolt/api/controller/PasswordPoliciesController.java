package com.jpassbolt.api.controller;

import com.jpassbolt.api.dto.PasswordPoliciesSettingsDto;
import com.jpassbolt.api.exception.PassboltApiException;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.UserRepository;
import com.jpassbolt.api.service.PasswordPoliciesService;
import com.jpassbolt.api.util.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * CE password-policies settings endpoint (port of the PHP PasswordPolicies
 * plugin {@code PasswordPoliciesSettingsGetController}):
 *
 * <ul>
 *   <li>GET /password-policies/settings.json — the org-wide password/passphrase
 *       generator policy.</li>
 * </ul>
 *
 * <p>
 * In CE these settings are ALWAYS the built-in defaults with
 * {@code source = "default"} (the DB write path is EE-only), so this is a pure
 * authenticated read with no admin gate and no storage — see
 * {@link PasswordPoliciesService#getSettings()} for the defaults.
 * </p>
 *
 * <p>
 * Authenticated only: {@code /password-policies/**} is not in SecurityConfig's
 * permitAll list, so it is covered by {@code .anyRequest().authenticated()} and
 * an anonymous call is rejected with 401 by the security chain before this
 * controller runs. The current user is resolved from the JWT principal exactly
 * like {@link AccountLocaleController} (principal name = username →
 * {@code UserRepository.findByUsername}); any authenticated user may read.
 * </p>
 *
 * <p>
 * Not part of the OpenAPI contract (plugin-redoc-0.yaml has no
 * {@code /password-policies/settings} path), so the companion contract test does
 * not run {@code openApi().isValid(...)} against this response — see
 * {@code PasswordPoliciesControllerContractTest} for the documented reason. The
 * envelope is still the standard {@link ApiResponse} shape.
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/password-policies")
@RequiredArgsConstructor
public class PasswordPoliciesController {

    private final PasswordPoliciesService passwordPoliciesService;
    private final UserRepository userRepository;

    /**
     * GET /password-policies/settings.json — return the CE password-policies
     * settings (always the defaults; PHP
     * {@code PasswordPoliciesSettingsGetController::get}). Success message is
     * "The operation was successful." and the body is the settings DTO.
     */
    @GetMapping({ "/settings", "/settings.json" })
    public ResponseEntity<Map<String, Object>> get() {
        String url = "/password-policies/settings.json";
        getCurrentUser(); // 401/404 guard via PassboltApiException

        PasswordPoliciesSettingsDto dto = passwordPoliciesService.getSettings();
        return ResponseEntity.ok(ApiResponse.success("The operation was successful.", dto, url));
    }

    /**
     * Resolve the current authenticated user from the JWT principal (its name
     * is the username). Same pattern as {@link AccountLocaleController}.
     */
    private User getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            throw new PassboltApiException(HttpStatus.UNAUTHORIZED, "No authenticated user");
        }
        String username = auth.getName();
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new PassboltApiException(HttpStatus.NOT_FOUND,
                        "User not found: " + username));
    }
}
