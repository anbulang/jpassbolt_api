package com.jpassbolt.api.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Typed configuration for the operation audit log ({@code action_logs}), the JPassbolt
 * analogue of PHP {@code passbolt.plugins.log.config}.
 *
 * <p>Overridable under the {@code jpassbolt.log} prefix in {@code application.yml}:
 * <pre>
 * jpassbolt:
 *   log:
 *     enabled: true
 *     blacklist:
 *       - AuthController.isAuthenticated
 *       - HealthcheckController.status
 * </pre></p>
 *
 * <p>The {@link #blacklist} holds JPassbolt-native action names
 * ({@code "<ControllerSimpleName>.<method>"}) for high-frequency polling endpoints that
 * would otherwise flood the log — the equivalent of PHP's blacklist of
 * {@code AuthIsAuthenticated.isAuthenticated} / {@code HealthcheckStatus.status} /
 * {@code AuthLogin.loginGet} / {@code TransfersView.view}. Only the first two have a
 * JPassbolt counterpart; the latter two endpoints do not exist here.</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "jpassbolt.log")
public class ActionLogProperties {

    /** Master switch for the operation audit log. Default on, matching PHP CE's {@code log.enabled=true}. */
    private boolean enabled = true;

    /** Action names that must not be logged (noise reduction). */
    private List<String> blacklist = defaultBlacklist();

    private static List<String> defaultBlacklist() {
        List<String> defaults = new ArrayList<>();
        defaults.add("AuthController.isAuthenticated"); // PHP AuthIsAuthenticated.isAuthenticated
        defaults.add("HealthcheckController.status");   // PHP HealthcheckStatus.status
        return defaults;
    }
}
