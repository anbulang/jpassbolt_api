package com.jpassbolt.api.service;

import com.jpassbolt.api.config.SettingsProperties;
import com.jpassbolt.api.model.OrganizationSetting;
import com.jpassbolt.api.repository.OrganizationSettingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * Builds the settings payload for {@code GET /settings.json}.
 *
 * <p>
 * Port of the PHP {@code SettingsIndexController::_getSettings()} /
 * {@code _getPluginWhiteList()} / {@code _getWhiteListedPluginConfig()} and
 * {@code GetOrgLocaleService}. The response detail level is two-tier:
 * </p>
 * <ul>
 * <li><b>guest</b> (anonymous): base settings (app.url, app.locale,
 * passbolt.legal, passbolt.edition) plus the public plugin whitelist (CE:
 * the {@link #PUBLIC_ENABLED_FLAGS} enabled flags, locale.options,
 * rememberMe.options)</li>
 * <li><b>authenticated</b> (user and admin alike — no admin privilege here):
 * additionally app.version, app.debug, app.server_timezone,
 * app.session_timeout, app.image_storage and the full plugin whitelist</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettingsService {

    /**
     * Default org locale exposed as {@code app.locale} in guest GET /settings.json.
     * MUST stay in sync with {@link AccountLocaleService#DEFAULT_LOCALE} (zh-CN)
     * so the skeleton page and outbound emails agree on the default language.
     */
    private static final String DEFAULT_LOCALE = AccountLocaleService.DEFAULT_LOCALE;

    /** PHP LocaleService::SETTING_PROPERTY. */
    private static final String LOCALE_PROPERTY = "locale";

    /**
     * Plugins whose {@code enabled} flag is publicly visible (guest view) —
     * the settingsVisibility.whiteListPublic entries of the reference
     * configs.
     */
    private static final List<String> PUBLIC_ENABLED_FLAGS = List.of(
            "jwtAuthentication",
            "accountRecoveryRequestHelp",
            "inFormIntegration",
            "selfRegistration",
            "userKeyPolicies");

    /**
     * Plugins loaded unconditionally by the reference
     * BaseSolutionBootstrapper (plain addPlugin, no
     * addFeaturePluginIfEnabled) whose configs define no {@code enabled}
     * key — Configure::check gates the alwaysWhiteListed pair, so the
     * reference never outputs {@code enabled} for them.
     */
    private static final List<String> NO_ENABLED_KEY = List.of(
            "locale",
            "passwordGenerator",
            "accountSettings");

    private final OrganizationSettingRepository organizationSettingRepository;
    private final SettingsProperties settingsProperties;

    @Value("${jpassbolt.jwt.expiration}")
    private long jwtExpirationMillis;

    /**
     * Build the settings map for the given authentication state.
     *
     * @param authenticated whether the caller is an authenticated user
     * @return ordered settings map with top-level keys {@code app} and
     *         {@code passbolt}
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getSettings(boolean authenticated) {
        // --- base settings (visible to everyone, guest included) ---
        Map<String, Object> app = new LinkedHashMap<>();
        app.put("url", settingsProperties.getFullBaseUrl());
        app.put("locale", getOrganizationLocale());

        Map<String, Object> privacyPolicy = new LinkedHashMap<>();
        privacyPolicy.put("url", settingsProperties.getPrivacyPolicyUrl());
        Map<String, Object> terms = new LinkedHashMap<>();
        terms.put("url", settingsProperties.getTermsUrl());
        Map<String, Object> legal = new LinkedHashMap<>();
        legal.put("privacy_policy", privacyPolicy);
        legal.put("terms", terms);

        Map<String, Object> passbolt = new LinkedHashMap<>();
        passbolt.put("legal", legal);
        passbolt.put("edition", settingsProperties.getEdition());

        // passbolt.email.validate.regex — like PHP (is_string check on
        // EmailValidationRule::REGEX_CHECK_KEY), the key is only present when
        // a custom regex is configured; CE default omits it entirely.
        if (settingsProperties.getEmailValidateRegex() != null) {
            Map<String, Object> validate = new LinkedHashMap<>();
            validate.put("regex", settingsProperties.getEmailValidateRegex());
            Map<String, Object> email = new LinkedHashMap<>();
            email.put("validate", validate);
            passbolt.put("email", email);
        }

        if (authenticated) {
            // --- authenticated-only app details ---
            Map<String, Object> version = new LinkedHashMap<>();
            version.put("number", settingsProperties.getVersionNumber());
            version.put("name", settingsProperties.getVersionName());
            app.put("version", version);
            app.put("debug", settingsProperties.isDebug() ? 1 : 0);
            app.put("server_timezone", TimeZone.getDefault().getID());
            // session timeout info in minutes (PHP reads Session.timeout);
            // derived from the JWT expiration here — never output milliseconds.
            app.put("session_timeout", jwtExpirationMillis / 60000);
            Map<String, Object> imageStorage = new LinkedHashMap<>();
            imageStorage.put("public_path", "");
            app.put("image_storage", imageStorage);

            passbolt.put("plugins", buildAuthenticatedPlugins());
        } else {
            passbolt.put("plugins", buildPublicPlugins());
        }

        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("app", app);
        settings.put("passbolt", passbolt);
        return settings;
    }

    /**
     * Full plugin whitelist for authenticated users: every configured
     * capability switch as {@code <plugin>.version} (enabled plugins with a
     * known reference version only — PHP never loads a disabled feature
     * plugin, so its config.php version never reaches Configure) and
     * {@code <plugin>.enabled} (the alwaysWhiteListed pair), plus the locale
     * and rememberMe options.
     */
    private Map<String, Object> buildAuthenticatedPlugins() {
        Map<String, Object> plugins = new LinkedHashMap<>();
        for (Map.Entry<String, Boolean> entry : settingsProperties.getPlugins().entrySet()) {
            boolean enabled = Boolean.TRUE.equals(entry.getValue());
            Map<String, Object> pluginConfig = new LinkedHashMap<>();
            String version = SettingsProperties.pluginVersion(entry.getKey());
            if (enabled && version != null) {
                pluginConfig.put("version", version);
            }
            if (!NO_ENABLED_KEY.contains(entry.getKey())) {
                pluginConfig.put("enabled", enabled);
            }
            plugins.put(entry.getKey(), pluginConfig);
        }
        // locale carries its whitelisted options next to version/enabled;
        // rememberMe has no enabled key at all in the reference (neither
        // default.php nor its config.php define one), only version + options.
        mergeIntoPlugin(plugins, "locale", localePlugin());
        Map<String, Object> rememberMe = new LinkedHashMap<>();
        rememberMe.put("version", SettingsProperties.pluginVersion("rememberMe"));
        rememberMe.putAll(rememberMePlugin());
        plugins.put("rememberMe", rememberMe);
        return plugins;
    }

    /**
     * Public (guest) plugin whitelist — the whiteListPublic entries of the
     * reference configs: {@code <plugin>.enabled} for
     * accountRecoveryRequestHelp (config/default.php), jwtAuthentication,
     * inFormIntegration, selfRegistration and userKeyPolicies (their
     * config.php), plus locale.options and rememberMe.options. No version
     * key leaks to guests.
     */
    private Map<String, Object> buildPublicPlugins() {
        Map<String, Object> plugins = new LinkedHashMap<>();
        for (String name : PUBLIC_ENABLED_FLAGS) {
            Map<String, Object> pluginConfig = new LinkedHashMap<>();
            pluginConfig.put("enabled", Boolean.TRUE.equals(
                    settingsProperties.getPlugins().getOrDefault(name, false)));
            plugins.put(name, pluginConfig);
        }
        plugins.put("locale", localePlugin());
        plugins.put("rememberMe", rememberMePlugin());
        return plugins;
    }

    /** Merge additional whitelisted keys into an already-built plugin entry. */
    private void mergeIntoPlugin(Map<String, Object> plugins, String name, Map<String, Object> extraConfig) {
        @SuppressWarnings("unchecked")
        Map<String, Object> pluginConfig = (Map<String, Object>) plugins
                .computeIfAbsent(name, k -> new LinkedHashMap<String, Object>());
        pluginConfig.putAll(extraConfig);
    }

    /**
     * Read the organization locale from organization_settings, falling back
     * to the default like PHP GetOrgLocaleService. Note: the DB row stores
     * the currently selected org locale (app.locale) — the selectable options
     * list below is a config constant, not DB data.
     */
    private String getOrganizationLocale() {
        return organizationSettingRepository.findByProperty(LOCALE_PROPERTY)
                .map(OrganizationSetting::getValue)
                .orElse(DEFAULT_LOCALE);
    }

    /** plugins.locale.{options} — constants from Locale/config/config.php. */
    private Map<String, Object> localePlugin() {
        List<Map<String, String>> options = new ArrayList<>();
        options.add(localeOption("de-DE", "Deutsch"));
        options.add(localeOption("en-UK", "English"));
        options.add(localeOption("es-ES", "Español"));
        options.add(localeOption("fr-FR", "Français"));
        options.add(localeOption("it-IT", "Italiano (beta)"));
        options.add(localeOption("ja-JP", "日本語"));
        options.add(localeOption("ko-KR", "한국어 (beta)"));
        options.add(localeOption("lt-LT", "Lietuvių"));
        options.add(localeOption("nl-NL", "Nederlands"));
        options.add(localeOption("pl-PL", "Polski"));
        options.add(localeOption("pt-BR", "Português Brasil (beta)"));
        options.add(localeOption("ro-RO", "Română (beta)"));
        options.add(localeOption("ru-RU", "Pусский (beta)"));
        options.add(localeOption("sv-SE", "Svenska"));
        options.add(localeOption("sl-SI", "Slovenščina"));
        options.add(localeOption("uk-UA", "Українська"));
        options.add(localeOption("cs-CZ", "Čeština (beta)"));
        // Project addition on top of the upstream Locale plugin config:
        // Chinese is selectable here and accepted by AccountLocaleService.
        options.add(localeOption("zh-CN", "中文"));

        Map<String, Object> locale = new LinkedHashMap<>();
        locale.put("options", options);
        return locale;
    }

    private Map<String, String> localeOption(String locale, String label) {
        Map<String, String> option = new LinkedHashMap<>();
        option.put("locale", locale);
        option.put("label", label);
        return option;
    }

    /**
     * plugins.rememberMe.{options} — fixed five durations from
     * RememberMe/config/config.php (string keys and string labels).
     */
    private Map<String, Object> rememberMePlugin() {
        Map<String, String> options = new LinkedHashMap<>();
        options.put("300", "5 minutes");
        options.put("900", "15 minutes");
        options.put("1800", "30 minutes");
        options.put("3600", "1 hour");
        options.put("-1", "until I log out");

        Map<String, Object> rememberMe = new LinkedHashMap<>();
        rememberMe.put("options", options);
        return rememberMe;
    }
}
