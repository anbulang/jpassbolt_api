package com.jpassbolt.api.controller;

import com.jpassbolt.api.exception.PassboltApiException;
import com.jpassbolt.api.model.AccountSetting;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.AccountSettingRepository;
import com.jpassbolt.api.repository.UserRepository;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Account settings index endpoint (port of the PHP AccountSettings plugin
 * {@code AccountSettingsIndexController::index}).
 *
 * <ul>
 * <li>GET /account/settings.json — return the caller's own
 * {@code account_settings} rows filtered to the whitelisted properties
 * {@code ['theme','locale']} (PHP {@code findIndex($this->User->id(),
 * ['theme','locale'])}).</li>
 * </ul>
 *
 * <p>
 * Authenticated only — no admin gate: any user reads their OWN settings.
 * {@code /account/**} is not in SecurityConfig's permitAll list, so an
 * anonymous call is rejected with 401 by the security chain before this
 * controller runs. The current user is resolved from the JWT principal exactly
 * like {@link AccountLocaleController} / {@link AccountThemeController}
 * (principal name = username → {@code UserRepository.findByUsername}).
 * </p>
 *
 * <p>
 * The body is the array of the caller's matching settings rows (PHP returns the
 * query result directly under {@code body}), each as a {@code {id, user_id,
 * property, value}} object — same row shape the theme/locale POST endpoints
 * return. The PHP finder filters by the UUIDv5-derived {@code property_id IN
 * (...)}; the Java port filters by the {@code (user_id, property)} pair
 * instead, consistent with the rest of {@link AccountSettingRepository}.
 * </p>
 *
 * <p>
 * Not part of the OpenAPI contract (plugin-redoc-0.yaml has no
 * {@code /account/settings} path — the only {@code /settings.json} entry is the
 * org-wide settings index at the API root), so the envelope is the standard
 * {@code ApiResponse} shape but no {@code openApi().isValid(...)} assertion runs
 * against it (same documented situation as the locale/theme endpoints — see
 * {@link AccountSettingsIndexControllerContractTest}).
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/account")
@RequiredArgsConstructor
public class AccountSettingsIndexController {

    /** Whitelist mirrored from PHP {@code findIndex(..., ['theme','locale'])}. */
    private static final List<String> WHITELIST = List.of("theme", "locale");

    private final AccountSettingRepository accountSettingRepository;
    private final UserRepository userRepository;

    /**
     * GET /account/settings.json — list the caller's own {@code theme} and
     * {@code locale} settings rows. Mirrors PHP
     * {@code AccountSettingsIndexController::index}: success message is "The
     * operation was successful." and the body is the array of matching rows.
     */
    @GetMapping({ "/settings", "/settings.json" })
    public ResponseEntity<Map<String, Object>> index() {
        String url = "/account/settings.json";
        String userId = getCurrentUser().getId();

        List<AccountSetting> settings = accountSettingRepository
                .findByUserIdAndPropertyIn(userId, WHITELIST);

        List<Map<String, Object>> body = new ArrayList<>();
        for (AccountSetting setting : settings) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", setting.getId());
            row.put("user_id", setting.getUserId());
            row.put("property", setting.getProperty());
            row.put("value", setting.getValue());
            body.add(row);
        }
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
}
