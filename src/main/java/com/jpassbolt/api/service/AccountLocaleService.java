package com.jpassbolt.api.service;

import com.jpassbolt.api.exception.PassboltApiException;
import com.jpassbolt.api.model.AccountSetting;
import com.jpassbolt.api.model.OrganizationSetting;
import com.jpassbolt.api.repository.AccountSettingRepository;
import com.jpassbolt.api.repository.OrganizationSettingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Per-user account locale, mirroring the PHP plugin trio
 * {@code GetUserLocaleService} / {@code SetUserLocaleService} /
 * {@code AccountLocalesSelectController} (Passbolt CE Locale plugin).
 *
 * <p>
 * The locale is stored as an {@code account_settings} row with
 * {@code property = "locale"} and {@code value = "<code>"} (e.g. "zh-CN").
 * Resolution order matches PHP {@code GetUserLocaleService::getLocale}:
 * user setting → organization setting → default {@value #DEFAULT_LOCALE}.
 * </p>
 *
 * <p>
 * Supported codes are the constant set from
 * {@code plugins/PassboltCe/Locale/config/config.php} (PHP
 * {@code LocaleService::getSystemLocales}), extended with {@code zh-CN}
 * (Chinese) which this project ships on top of upstream. Validation matches
 * PHP {@code LocaleService::assertIsValidLocale}: a dasherized, case-sensitive
 * membership check, rejecting anything else with a 400.
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountLocaleService {

    /** PHP GetOrgLocaleService::DEFAULT_LOCALE. */
    public static final String DEFAULT_LOCALE = "en-UK";

    /** PHP LocaleService::SETTING_PROPERTY. */
    public static final String LOCALE_PROPERTY = "locale";

    /** Seed for the deterministic property_id (mirrors MfaService pattern). */
    private static final String ACCOUNT_PROPERTY_ID_SEED = "account.setting.locale";

    /**
     * Supported locale codes — the PHP Locale plugin config.php list plus the
     * project-added {@code zh-CN}. Order is the PHP order with Chinese
     * appended. Membership is checked against the dasherized form.
     */
    private static final Set<String> SUPPORTED_LOCALES = Set.of(
            "de-DE", "en-UK", "es-ES", "fr-FR", "it-IT", "ja-JP", "ko-KR",
            "lt-LT", "nl-NL", "pl-PL", "pt-BR", "ro-RO", "ru-RU", "sv-SE",
            "sl-SI", "uk-UA", "cs-CZ", "zh-CN");

    private final AccountSettingRepository accountSettingRepository;
    private final OrganizationSettingRepository organizationSettingRepository;

    /**
     * Resolve the effective locale for a user: the user's account setting,
     * else the organization locale, else {@value #DEFAULT_LOCALE} (PHP
     * {@code GetUserLocaleService::getLocale}).
     *
     * @param userId the user UUID
     * @return a non-null locale code
     */
    @Transactional(readOnly = true)
    public String getUserLocale(String userId) {
        return accountSettingRepository.findFirstByUserIdAndProperty(userId, LOCALE_PROPERTY)
                .map(AccountSetting::getValue)
                .filter(value -> value != null && !value.isBlank())
                .orElseGet(this::getOrganizationLocale);
    }

    /**
     * The organization locale from organization_settings(property='locale'),
     * falling back to the default (PHP {@code GetOrgLocaleService::getLocale}).
     */
    public String getOrganizationLocale() {
        return organizationSettingRepository.findByProperty(LOCALE_PROPERTY)
                .map(OrganizationSetting::getValue)
                .filter(value -> value != null && !value.isBlank())
                .orElse(DEFAULT_LOCALE);
    }

    /**
     * Validate and upsert the user's locale account setting (PHP
     * {@code SetUserLocaleService::save} →
     * {@code AccountSettingsTable::createOrUpdateSetting}).
     *
     * @param userId the user UUID
     * @param value  the requested locale code
     * @return the persisted/updated entity
     * @throws PassboltApiException 400 when the locale is not supported
     */
    @Transactional
    public AccountSetting setUserLocale(String userId, String value) {
        assertIsValidLocale(value);
        String locale = dasherize(value);

        AccountSetting setting = accountSettingRepository
                .findFirstByUserIdAndProperty(userId, LOCALE_PROPERTY)
                .orElseGet(() -> {
                    AccountSetting created = new AccountSetting();
                    created.setUserId(userId);
                    created.setProperty(LOCALE_PROPERTY);
                    created.setPropertyId(deterministicUuid(ACCOUNT_PROPERTY_ID_SEED));
                    return created;
                });
        setting.setValue(locale);
        return accountSettingRepository.save(setting);
    }

    /**
     * Map a Passbolt locale code to a {@link Locale} (used by the email task
     * to render in the recipient's language). "zh-CN" → zh_CN, "en-UK" → en
     * (UK is not a JDK region; only the language is meaningful). A blank or
     * null code yields the default locale.
     *
     * @param code the Passbolt locale code
     * @return the corresponding Java locale
     */
    public Locale toJavaLocale(String code) {
        if (code == null || code.isBlank()) {
            code = DEFAULT_LOCALE;
        }
        String[] parts = dasherize(code).split("-", 2);
        String language = parts[0].toLowerCase(Locale.ROOT);
        if (parts.length < 2) {
            return new Locale(language);
        }
        String region = parts[1].toUpperCase(Locale.ROOT);
        // "en-UK" is a Passbolt-ism: UK is not an ISO 3166 region, GB is.
        // The language alone is what matters for message lookup, so drop the
        // pseudo-region for UK to avoid an unusable en_UK locale.
        if ("UK".equals(region)) {
            return new Locale(language);
        }
        return new Locale(language, region);
    }

    /**
     * Throw a 400 when the locale is not in {@link #SUPPORTED_LOCALES}
     * (PHP {@code LocaleService::assertIsValidLocale}).
     */
    private void assertIsValidLocale(String locale) {
        if (!isValidLocale(locale)) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST, "This is not a valid locale.");
        }
    }

    /**
     * Membership check against the supported set, comparing the dasherized
     * form (both {@code zh_CN} and {@code zh-CN} are accepted on input, PHP
     * {@code LocaleService::isValidLocale}). A null/blank value is invalid.
     */
    public boolean isValidLocale(String locale) {
        if (locale == null || locale.isBlank()) {
            return false;
        }
        return SUPPORTED_LOCALES.contains(dasherize(locale));
    }

    /** PHP LocaleService::dasherizeLocale — underscore → dash. */
    private String dasherize(String locale) {
        return locale == null ? "" : locale.replace('_', '-');
    }

    /**
     * Deterministic property_id from a seed (mirrors MfaService): a UUIDv3
     * over the seed. Queries always go by (user_id, property), never by
     * property_id, so the exact value is internally consistent only.
     */
    private String deterministicUuid(String seed) {
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
