package com.jpassbolt.api.controller;

import com.jpassbolt.api.exception.PassboltApiException;
import com.jpassbolt.api.model.AccountSetting;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.UserRepository;
import com.jpassbolt.api.service.AccountLocaleService;
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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Per-user account locale endpoints (port of the PHP Locale plugin
 * {@code AccountLocalesSelectController}):
 *
 * <ul>
 * <li>POST /account/settings/locales.json — create/update the caller's locale
 * ({@code {"value":"zh-CN"}}); 400 on an unsupported locale</li>
 * <li>GET /account/settings/locales.json — read the caller's effective locale
 * (user setting → organization → default; a convenience read with no PHP
 * counterpart)</li>
 * </ul>
 *
 * <p>
 * Authenticated only: {@code /account/**} is not in SecurityConfig's permitAll
 * list, so it is covered by {@code .anyRequest().authenticated()} and an
 * anonymous call is rejected with 401 by the security chain before this
 * controller runs. The current user is resolved from the JWT principal exactly
 * like {@code MfaController} (principal name = username →
 * {@code UserRepository.findByUsername}).
 * </p>
 *
 * <p>
 * Not part of the OpenAPI contract (plugin-redoc-0.yaml has no
 * {@code /account/settings/locales} path), so the companion contract test does
 * not run {@code openApi().isValid(...)} against these responses — see
 * {@code AccountLocaleControllerContractTest} for the documented reason. The
 * envelope is still the standard {@code ApiResponse} shape.
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/account/settings")
@RequiredArgsConstructor
public class AccountLocaleController {

    private final AccountLocaleService accountLocaleService;
    private final UserRepository userRepository;

    /**
     * POST /account/settings/locales.json — validate and persist the caller's
     * locale. Mirrors PHP {@code AccountLocalesSelectController::select}: the
     * service throws a 400 ("This is not a valid locale.") for an unsupported
     * value; the success message is "The operation was successful." and the
     * body is the saved setting.
     */
    @PostMapping({ "/locales", "/locales.json" })
    public ResponseEntity<Map<String, Object>> select(
            @RequestBody(required = false) LocaleRequest request) {
        String url = "/account/settings/locales.json";
        String userId = getCurrentUser().getId();
        String value = request != null ? request.getValue() : null;

        AccountSetting setting = accountLocaleService.setUserLocale(userId, value);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", setting.getId());
        body.put("user_id", setting.getUserId());
        body.put("property", setting.getProperty());
        body.put("value", setting.getValue());
        return ResponseEntity.ok(ApiResponse.success("The operation was successful.", body, url));
    }

    /**
     * GET /account/settings/locales.json — return the caller's effective
     * locale (user → organization → default). Convenience read; no PHP
     * counterpart.
     */
    @GetMapping({ "/locales", "/locales.json" })
    public ResponseEntity<Map<String, Object>> view() {
        String url = "/account/settings/locales.json";
        String userId = getCurrentUser().getId();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("value", accountLocaleService.getUserLocale(userId));
        return ResponseEntity.ok(ApiResponse.success("The operation was successful.", body, url));
    }

    /**
     * Resolve the current authenticated user from the JWT principal (its name
     * is the username). Same pattern as {@code MfaController.getCurrentUser}.
     */
    private User getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            throw new PassboltApiException(HttpStatus.UNAUTHORIZED, "No authenticated user");
        }
        String username = auth.getName();
        Optional<User> user = userRepository.findByUsername(username);
        return user.orElseThrow(() -> new PassboltApiException(HttpStatus.NOT_FOUND,
                "User not found: " + username));
    }

    /**
     * Request body for POST: {@code {"value":"zh-CN"}} (PHP
     * {@code LocaleService::REQUEST_DATA_KEY = "value"}). Plain transport DTO,
     * no business logic.
     */
    public static class LocaleRequest {
        private String value;

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }
    }
}
