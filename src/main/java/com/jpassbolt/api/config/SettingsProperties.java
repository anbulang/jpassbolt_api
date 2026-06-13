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
    private String fullBaseUrl = "http://localhost:8080";

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
     * Capability switches, keyed by plugin name in camelCase (must match the
     * names the official extension probes for).
     */
    private Map<String, Boolean> plugins = defaultPlugins();

    private static Map<String, Boolean> defaultPlugins() {
        Map<String, Boolean> defaults = new LinkedHashMap<>();
        // Implemented in JPassbolt.
        defaults.put("jwtAuthentication", true);
        defaults.put("previewPassword", true);
        defaults.put("multiFactorAuthentication", true);
        // Publicly visible flag in CE; feature not implemented yet.
        defaults.put("accountRecoveryRequestHelp", false);
        // Not implemented yet — must stay false until their clusters land.
        defaults.put("export", false);
        defaults.put("import", false);
        defaults.put("mobile", false);
        defaults.put("desktop", false);
        return defaults;
    }
}
