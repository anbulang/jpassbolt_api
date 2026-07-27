package com.jpassbolt.api.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Typed configuration for the server settings endpoint.
 *
 * <p>
 * Plays the role of PHP's {@code Configure::read(...)} for the values exposed
 * by {@code GET /settings.json} (compare with the reference
 * {@code config/default.php} and {@code config/version.php}). All fields carry
 * code-level defaults; {@code application.yml} may override them under the
 * {@code jpassbolt.settings} prefix.
 * </p>
 *
 * <p>
 * {@link #plugins} is the capability switchboard read by the official browser
 * extension: each entry is rendered as {@code passbolt.plugins.<name>.enabled}
 * for authenticated callers. Plugins that are not yet implemented in
 * JPassbolt must stay {@code false} (or absent) — advertising them would make
 * the extension call endpoints that do not exist. Later feature clusters flip
 * their own switch to {@code true} once implemented.
 * </p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "jpassbolt.settings")
public class SettingsProperties {

    /**
     * Full base URL of the site, the equivalent of PHP
     * {@code Router::url('/', true)}. The official browser extension uses
     * this value for domain trust matching, so it must match the deployed
     * origin (to be confirmed during integration with the extension whether
     * the {@code /api} context path belongs here).
     */
    private String fullBaseUrl = "http://localhost:8090";

    /** Passbolt edition identifier ("ce" for Community Edition). */
    private String edition = "ce";

    /** Advertised application version number (see reference version.php). */
    private String versionNumber = "5.7.2";

    /** Advertised application version name (see reference version.php). */
    private String versionName = "Gnossienne No. 1";

    /** passbolt.legal.privacy_policy.url — empty by default, like CE. */
    private String privacyPolicyUrl = "";

    /** passbolt.legal.terms.url — CE default. */
    private String termsUrl = "https://www.passbolt.com/terms";

    /** Rendered as app.debug 1/0 for authenticated callers. */
    private boolean debug = false;

    /**
     * passbolt.email.validate.regex — custom email validation regex the
     * clients apply instead of their default. Like PHP (EmailValidationRule
     * REGEX_CHECK_KEY), the key is only emitted when a value is configured;
     * CE default is null/absent.
     */
    private String emailValidateRegex;

    /**
     * Capability switches, keyed by plugin name in camelCase (must match the
     * names the official extension probes for).
     */
    private Map<String, Boolean> plugins = defaultPlugins();

    /**
     * Advertised plugin versions, mirroring the {@code version} key of the
     * reference plugin configs (passbolt_api_ref/plugins/PassboltCe/&lt;Plugin&gt;/
     * config/config.php). Emitted next to {@code enabled} for enabled plugins
     * only — in PHP a disabled feature plugin is never loaded, so its
     * config.php version never reaches Configure. Plugins whose reference
     * config carries no version (previewPassword, accountRecoveryRequestHelp,
     * disableUser, desktop, healthcheck, healthcheckUi — they only exist in
     * config/default.php) have no entry here.
     */
    private static final Map<String, String> PLUGIN_VERSIONS = Map.ofEntries(
            Map.entry("jwtAuthentication", "3.3.0"),
            Map.entry("multiFactorAuthentication", "1.1.0"),
            Map.entry("selfRegistration", "1.0.0"),
            Map.entry("smtpSettings", "1.0.0"),
            Map.entry("folders", "2.0.0"),
            Map.entry("resourceTypes", "1.0.0"),
            Map.entry("totpResourceTypes", "1.0.0"),
            Map.entry("metadata", "1.0.0"),
            Map.entry("passwordPolicies", "1.0.0"),
            Map.entry("passwordGenerator", "4.2.0"),
            Map.entry("emailNotificationSettings", "1.1.0"),
            Map.entry("accountSettings", "1.0.0"),
            Map.entry("userKeyPolicies", "1.0.0"),
            Map.entry("log", "1.0.1"),
            Map.entry("inFormIntegration", "1.0.0"),
            Map.entry("locale", "3.2.0"),
            Map.entry("rememberMe", "2.0.0"),
            Map.entry("export", "2.0.0"),
            Map.entry("import", "2.0.1"),
            Map.entry("mobile", "1.0.0"),
            Map.entry("rbacs", "1.0.0"),
            Map.entry("passwordExpiry", "1.0.0"),
            Map.entry("secretRevisions", "1.0.0"),
            Map.entry("emailDigest", "1.0.0"),
            Map.entry("reports", "1.0.0"));

    /**
     * Reference version of the given plugin, or null when the reference
     * config advertises none.
     */
    public static String pluginVersion(String name) {
        return PLUGIN_VERSIONS.get(name);
    }

    private static Map<String, Boolean> defaultPlugins() {
        Map<String, Boolean> defaults = new LinkedHashMap<>();
        // Implemented in JPassbolt.
        defaults.put("jwtAuthentication", true);
        defaults.put("previewPassword", true);
        defaults.put("multiFactorAuthentication", true);
        // Guest self-registration (SelfRegistrationController + settings). The
        // flag advertises that the plugin EXISTS on this server (PHP feature
        // plugin loaded); whether registration is actually OPEN is a separate
        // state — it requires an admin to configure allowed domains via
        // POST /self-registration/settings.json (with no settings row,
        // isSelfRegistrationOpen() is false and every attempt is rejected).
        // Override to false under jpassbolt.settings.plugins to hide the
        // register affordance entirely.
        defaults.put("selfRegistration", true);
        // Admin SMTP settings page (SmtpSettingsController + service). Advertises
        // that the /smtp/* endpoints exist on this server (PHP feature plugin
        // loaded); the actual config is a separate state — source "db" only once
        // an admin saves it via POST /smtp/settings.json, else it falls back to the
        // static spring.mail.* (source "env") or "undefined".
        defaults.put("smtpSettings", true);
        // FolderController + MoveController (folders CRUD and /move/folder).
        defaults.put("folders", true);
        // ResourceTypeController (index/view; write endpoints are v5-only).
        defaults.put("resourceTypes", true);
        // Standalone totp / password-description-totp types are seeded by
        // DataInitializer and served by ResourceTypeController.
        defaults.put("totpResourceTypes", true);
        // v5 metadata cluster: MetadataKey/Settings/SessionKey/Upgrade/
        // RotateKey controllers.
        defaults.put("metadata", true);
        // PasswordPoliciesController (GET /password-policies/settings.json —
        // also the target of the reference /password-generator/settings
        // redirect, deprecated since v5).
        defaults.put("passwordPolicies", true);
        // Client-side generator capability; no server endpoint of its own
        // (the reference plugin only redirects to /password-policies).
        defaults.put("passwordGenerator", true);
        // EmailNotificationSettingsController (GET/POST
        // /settings/emails/notifications.json).
        defaults.put("emailNotificationSettings", true);
        // AccountSettingsIndex/AccountLocale/AccountTheme controllers.
        defaults.put("accountSettings", true);
        // Org locale write endpoint (OrganizationLocaleController,
        // POST /locale/settings.json); the selectable options are advertised
        // separately under plugins.locale.options.
        defaults.put("locale", true);
        // Users PUT accepts the disabled/suspend field, including the
        // {"disabled": null} re-enable form (UsersController).
        defaults.put("disableUser", true);
        // UserKeyPoliciesController (GET /user-key-policies/settings.json).
        defaults.put("userKeyPolicies", true);
        // HealthCheckController (GET /healthcheck.json index + /status). The
        // index endpoint is toggled via jpassbolt.healthcheck.index-endpoint-
        // enabled (reference healthcheck.security.indexEndpointEnabled) — the
        // security sub-key has no settingsVisibility whitelist in the
        // reference, so settings.json only advertises enabled.
        defaults.put("healthcheck", true);
        // Client-side admin healthcheck UI consuming the endpoints above.
        defaults.put("healthcheckUi", true);
        // Write-side action_logs/secret_accesses audit, same surface as the
        // reference CE Log plugin (no read endpoints in CE — reads are EE).
        defaults.put("log", true);
        // Pure client-side capabilities (no server endpoint in the reference
        // either) — advertised so the extension enables its in-form menu.
        defaults.put("inFormIntegration", true);
        // Publicly visible help flag in CE (config/default.php hardcodes it true,
        // whiteListPublic=['enabled']). Pure advertised boolean — no server endpoint,
        // no other consumer — so it aligns the value with CE without wiring a backend;
        // the extension surfaces the account-recovery help affordance. NOT the EE
        // accountRecovery policy plugin (absent here).
        defaults.put("accountRecoveryRequestHelp", true);
        // Not implemented yet — must stay false until their clusters land.
        // export/import stay false as well: the reference plugins are the
        // server-side kdbx/csv endpoints, which the self-built frontend
        // CSV/KDBX import/export does NOT provide.
        defaults.put("export", false);
        defaults.put("import", false);
        defaults.put("mobile", false);
        defaults.put("desktop", false);
        // /rbacs endpoints (GET /rbacs/me.json) not implemented.
        defaults.put("rbacs", false);
        // /password-expiry endpoints not implemented.
        defaults.put("passwordExpiry", false);
        // Secret revisions endpoints not implemented.
        defaults.put("secretRevisions", false);
        // Email digest has no runtime scheduler here.
        defaults.put("emailDigest", false);
        // /reports endpoints not implemented.
        defaults.put("reports", false);
        // tags: intentionally NOT advertised (no key at all) although
        // TagController exists — Tags is an EE plugin and edition=ce servers
        // never list it; add jpassbolt.settings.plugins.tags=true to opt in.
        return defaults;
    }
}
