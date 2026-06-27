package com.jpassbolt.api.controller;

import com.jpassbolt.api.exception.PassboltApiException;
import com.jpassbolt.api.model.AccountSetting;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.UserRepository;
import com.jpassbolt.api.service.AccountThemeService;
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
 * Per-user account theme endpoints (port of the PHP AccountSettings plugin
 * {@code ThemesIndexController} / {@code ThemesSelectController}). The twin of
 * {@link AccountLocaleController}.
 *
 * <ul>
 * <li>POST /account/settings/themes.json — select the caller's theme
 * ({@code {"value":"midgar"}}); 400 ("This theme is not supported.") otherwise</li>
 * <li>GET /account/settings/themes.json — read the caller's current theme plus
 * the list of available themes</li>
 * </ul>
 *
 * <p>
 * Authenticated only: {@code /account/**} is not in SecurityConfig's permitAll
 * list, so an anonymous call is rejected with 401 by the security chain before
 * this controller runs. The current user is resolved from the JWT principal
 * exactly like {@link AccountLocaleController} / {@code MfaController}.
 * </p>
 *
 * <p>
 * Not part of the OpenAPI contract (plugin-redoc-0.yaml has no
 * {@code /account/settings/themes} path), so the envelope is the standard
 * {@code ApiResponse} shape but no {@code openApi().isValid(...)} assertion runs
 * against it (same documented situation as the locale endpoints).
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/account/settings")
@RequiredArgsConstructor
public class AccountThemeController {

    private final AccountThemeService accountThemeService;
    private final UserRepository userRepository;

    /**
     * POST /account/settings/themes.json — validate and persist the caller's
     * theme (PHP {@code ThemesSelectController::select}). The service throws a
     * 400 for an unsupported value; success message is "The operation was
     * successful." and the body is the saved setting.
     */
    @PostMapping({ "/themes", "/themes.json" })
    public ResponseEntity<Map<String, Object>> select(
            @RequestBody(required = false) ThemeRequest request) {
        String url = "/account/settings/themes.json";
        String userId = getCurrentUser().getId();
        String value = request != null ? request.getValue() : null;

        AccountSetting setting = accountThemeService.setUserTheme(userId, value);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", setting.getId());
        body.put("user_id", setting.getUserId());
        body.put("property", setting.getProperty());
        body.put("value", setting.getValue());
        return ResponseEntity.ok(ApiResponse.success("The operation was successful.", body, url));
    }

    /**
     * GET /account/settings/themes.json — return the caller's current theme
     * (PHP {@code ThemeSettingsTrait::getThemeOrDefault}) and the available
     * theme names (PHP {@code ThemesIndexController::index}).
     */
    @GetMapping({ "/themes", "/themes.json" })
    public ResponseEntity<Map<String, Object>> view() {
        String url = "/account/settings/themes.json";
        String userId = getCurrentUser().getId();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("value", accountThemeService.getUserTheme(userId));
        body.put("options", accountThemeService.availableThemes());
        return ResponseEntity.ok(ApiResponse.success("The operation was successful.", body, url));
    }

    /**
     * Resolve the current authenticated user from the JWT principal (its name
     * is the username). Same pattern as {@code AccountLocaleController}.
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
     * Request body for POST: {@code {"value":"midgar"}}. Plain transport DTO,
     * no business logic.
     */
    public static class ThemeRequest {
        private String value;

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }
    }
}
