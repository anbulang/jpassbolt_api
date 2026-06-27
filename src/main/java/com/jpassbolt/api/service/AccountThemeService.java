package com.jpassbolt.api.service;

import com.jpassbolt.api.exception.PassboltApiException;
import com.jpassbolt.api.model.AccountSetting;
import com.jpassbolt.api.repository.AccountSettingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Per-user account theme, mirroring the PHP AccountSettings plugin
 * {@code ThemesIndexController} / {@code ThemesSelectController} (and
 * {@code ThemeSettingsTrait::isValidTheme}). The twin of {@link AccountLocaleService}.
 *
 * <p>
 * The theme is stored as an {@code account_settings} row with
 * {@code property = "theme"} and {@code value} in {@link #SUPPORTED_THEMES}
 * ({@code default} = light, {@code midgar} = dark — Passbolt's two shipped
 * themes). Unlike locale there is NO organization-level theme in Passbolt, so
 * resolution is simply user setting → {@value #DEFAULT_THEME}.
 * </p>
 *
 * <p>
 * Validation mirrors PHP {@code ThemeSettingsTrait::isValidTheme} (membership
 * in the available-theme names), rejecting anything else with a 400
 * ("This theme is not supported.").
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountThemeService {

    /** Passbolt's default (light) theme name. */
    public static final String DEFAULT_THEME = "default";

    /** PHP AccountSettings property name for the theme. */
    public static final String THEME_PROPERTY = "theme";

    /** Seed for the deterministic property_id (mirrors AccountLocaleService). */
    private static final String ACCOUNT_PROPERTY_ID_SEED = "account.setting.theme";

    /**
     * Passbolt's shipped themes (scanned from {@code img/themes} in PHP): the
     * light {@code default} and the dark {@code midgar}. We ship exactly these
     * two so the SPA's light/dark map onto them 1:1.
     */
    private static final Set<String> SUPPORTED_THEMES = Set.of("default", "midgar");

    private final AccountSettingRepository accountSettingRepository;

    /**
     * The caller's theme, or {@value #DEFAULT_THEME} when unset
     * (PHP {@code ThemeSettingsTrait::getThemeOrDefault}).
     *
     * @param userId the user UUID
     * @return a non-null theme name
     */
    @Transactional(readOnly = true)
    public String getUserTheme(String userId) {
        return accountSettingRepository.findFirstByUserIdAndProperty(userId, THEME_PROPERTY)
                .map(AccountSetting::getValue)
                .filter(value -> value != null && !value.isBlank())
                .orElse(DEFAULT_THEME);
    }

    /** The selectable theme names (PHP {@code ThemesIndexController::index}). */
    public List<String> availableThemes() {
        return List.of("default", "midgar");
    }

    /**
     * Validate and upsert the caller's theme account setting (PHP
     * {@code ThemesSelectController::select}).
     *
     * @param userId the user UUID
     * @param value  the requested theme name
     * @return the persisted/updated entity
     * @throws PassboltApiException 400 when the theme is not supported
     */
    @Transactional
    public AccountSetting setUserTheme(String userId, String value) {
        assertIsValidTheme(value);

        AccountSetting setting = accountSettingRepository
                .findFirstByUserIdAndProperty(userId, THEME_PROPERTY)
                .orElseGet(() -> {
                    AccountSetting created = new AccountSetting();
                    created.setUserId(userId);
                    created.setProperty(THEME_PROPERTY);
                    created.setPropertyId(deterministicUuid(ACCOUNT_PROPERTY_ID_SEED));
                    return created;
                });
        setting.setValue(value);
        return accountSettingRepository.save(setting);
    }

    /** Membership check against {@link #SUPPORTED_THEMES}; null/blank is invalid. */
    public boolean isValidTheme(String value) {
        return value != null && !value.isBlank() && SUPPORTED_THEMES.contains(value);
    }

    private void assertIsValidTheme(String value) {
        if (!isValidTheme(value)) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST, "This theme is not supported.");
        }
    }

    /** Deterministic property_id from a seed (mirrors AccountLocaleService). */
    private String deterministicUuid(String seed) {
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
