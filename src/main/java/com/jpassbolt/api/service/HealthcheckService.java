package com.jpassbolt.api.service;

import com.jpassbolt.api.repository.RoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.File;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Assembles the healthcheck report served by GET /healthcheck.json.
 *
 * <p>
 * Mirrors the legacy JSON format produced by the PHP
 * {@code HealthcheckIndexController::formatCollectionResponseAsPerLegacy()}
 * (see {@code passbolt_api_ref}). The body is a fixed, deeply nested structure
 * with <b>camelCase</b> keys — this is a deliberate exception to the project's
 * snake_case DTO convention, required for plugin/CLI compatibility. No DTO
 * classes are used; the report is assembled with {@link LinkedHashMap}s
 * (precedent: ShareController's hand-built maps).
 * </p>
 *
 * <p>
 * Real checks: database connectivity ({@link DataSource}), table count
 * (JDBC {@code DatabaseMetaData} — works on both H2 and MySQL), default
 * content ({@code roles} table non-empty) and a GPG encrypt/decrypt
 * round-trip via {@link GpgService} (Bouncy Castle only). PHP-specific
 * checks (pcre/mbstring/intl/gnupg/image/phpVersion...) are emitted as
 * fixed {@code true} values to satisfy the OpenAPI contract. No outbound
 * HTTP request is ever made (latestVersion / fullBaseUrlReachable are
 * fixed values and {@code info.remoteVersion} mirrors the current version).
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HealthcheckService {

    private final DataSource dataSource;
    private final GpgService gpgService;
    private final RoleRepository roleRepository;

    @Value("${jpassbolt.version:4.9.0}")
    private String currentVersion;

    @Value("${server.servlet.context-path:/api}")
    private String contextPath;

    @Value("${server.port:8080}")
    private String serverPort;

    @Value("${jpassbolt.jwt.secret:}")
    private String jwtSecret;

    @Value("${jpassbolt.gpg.server-key.private-location:classpath:gpg/server_private.asc}")
    private String gpgPrivateKeyLocation;

    /**
     * Build the full healthcheck report.
     *
     * <p>
     * Domain order follows the OpenAPI schema / PHP legacy output:
     * environment, configFile, core, ssl, smtpSettings, gpg, application,
     * database. Note the key is {@code application} (singular — per schema
     * and PHP {@code HealthcheckServiceCollector::DOMAIN_APPLICATION}); the
     * spec example erroneously shows "applications". The {@code jwt} domain
     * is excluded (PHP {@code getDomainsIgnore()}).
     * </p>
     *
     * @return ordered map of the eight healthcheck domains
     */
    public Map<String, Object> getHealthcheckReport() {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("environment", checkEnvironment());
        report.put("configFile", checkConfigFile());
        report.put("core", checkCore());
        report.put("ssl", checkSsl());
        report.put("smtpSettings", checkSmtpSettings());
        report.put("gpg", checkGpg());
        report.put("application", checkApplication());
        report.put("database", checkDatabase());
        return report;
    }

    private Map<String, Object> checkEnvironment() {
        Map<String, Object> environment = new LinkedHashMap<>();
        // PHP-runtime specific checks: fixed true (no PHP in JPassbolt).
        environment.put("gnupg", true);
        Map<String, Object> info = new LinkedHashMap<>();
        // Contract only requires a string; report the JVM version instead.
        info.put("phpVersion", System.getProperty("java.version"));
        environment.put("info", info);
        environment.put("phpVersion", true);
        environment.put("nextMinPhpVersion", true);
        environment.put("pcre", true);
        environment.put("mbstring", true);
        environment.put("intl", true);
        environment.put("image", true);
        boolean tmpWritable = false;
        try {
            File tmpDir = new File(System.getProperty("java.io.tmpdir"));
            tmpWritable = tmpDir.isDirectory() && tmpDir.canWrite();
        } catch (Exception e) {
            log.warn("Healthcheck: tmp dir check failed", e);
        }
        environment.put("tmpWritable", tmpWritable);
        // Logs go to stdout via Logback; always considered writable.
        environment.put("logWritable", true);
        return environment;
    }

    private Map<String, Object> checkConfigFile() {
        Map<String, Object> configFile = new LinkedHashMap<>();
        // application.yml is on the classpath; if the context is up, config exists.
        configFile.put("app", true);
        configFile.put("passbolt", true);
        return configFile;
    }

    private Map<String, Object> checkCore() {
        String fullBaseUrl = "http://localhost:" + serverPort + contextPath;
        Map<String, Object> core = new LinkedHashMap<>();
        core.put("cache", true);
        core.put("debugDisabled", true);
        // "salt" maps to the JWT signing secret being configured.
        core.put("salt", jwtSecret != null && !jwtSecret.isBlank());
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("fullBaseUrl", fullBaseUrl);
        core.put("info", info);
        core.put("fullBaseUrl", true);
        core.put("validFullBaseUrl", true);
        // Never self-request the base URL: fixed value (no outbound HTTP).
        core.put("fullBaseUrlReachable", true);
        return core;
    }

    private Map<String, Object> checkSsl() {
        Map<String, Object> ssl = new LinkedHashMap<>();
        ssl.put("info", "SSL checks are not performed by JPassbolt; TLS termination is expected at the reverse proxy.");
        ssl.put("peerValid", false);
        ssl.put("hostValid", false);
        ssl.put("notSelfSigned", false);
        return ssl;
    }

    private Map<String, Object> checkSmtpSettings() {
        Map<String, Object> smtpSettings = new LinkedHashMap<>();
        // SMTP is not implemented in JPassbolt yet.
        smtpSettings.put("isEnabled", false);
        // false means "no validation error" (PHP outputs the error string otherwise).
        smtpSettings.put("errorMessage", false);
        smtpSettings.put("source", "undefined");
        smtpSettings.put("isInDb", false);
        smtpSettings.put("areEndpointsDisabled", false);
        smtpSettings.put("customSslOptions", false);
        return smtpSettings;
    }

    private Map<String, Object> checkGpg() {
        String publicKey = null;
        String fingerprint = null;
        boolean encryptOk = false;
        boolean decryptOk = false;
        try {
            publicKey = gpgService.getServerPublicKey();
            fingerprint = gpgService.getServerKeyFingerprint();
            if (publicKey != null && !publicKey.isBlank()) {
                String sample = "healthcheck-" + UUID.randomUUID();
                String encrypted = gpgService.encrypt(sample, publicKey);
                encryptOk = encrypted != null && !encrypted.isBlank();
                decryptOk = encryptOk && sample.equals(gpgService.decrypt(encrypted));
            }
        } catch (Exception e) {
            log.warn("Healthcheck: GPG round-trip check failed", e);
        }
        boolean publicKeyLoaded = publicKey != null && publicKey.contains("BEGIN PGP PUBLIC KEY BLOCK");
        boolean fingerprintValid = fingerprint != null && fingerprint.length() == 40;

        Map<String, Object> gpg = new LinkedHashMap<>();
        // Bouncy Castle is a compile-time dependency: if this service exists, the lib is there.
        gpg.put("lib", true);
        // In-memory Bouncy Castle keyring: no GnuPG home directory involved.
        gpg.put("gpgHome", true);
        gpg.put("gpgHomeWritable", true);
        // Required by the schema (L7911) even though its "properties" entry is missing.
        gpg.put("gpgKeyNotDefault", true);
        gpg.put("gpgKeyPublicBlock", publicKeyLoaded);
        gpg.put("gpgKeyPrivateBlock", decryptOk);
        gpg.put("gpgKeyPublicReadable", publicKeyLoaded);
        gpg.put("gpgKeyPrivateReadable", decryptOk);
        gpg.put("gpgKeyPrivateFingerprint", fingerprintValid);
        gpg.put("gpgKeyPublic", publicKeyLoaded);
        gpg.put("gpgKeyPrivate", decryptOk);
        gpg.put("gpgKey", fingerprintValid);
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("gpgKeyPrivate", gpgPrivateKeyLocation);
        info.put("gpgHome", "N/A (Bouncy Castle in-memory keyring)");
        gpg.put("info", info);
        gpg.put("gpgKeyPublicFingerprint", fingerprintValid);
        gpg.put("gpgKeyPublicInKeyring", publicKeyLoaded);
        gpg.put("gpgKeyPublicEmail", true);
        gpg.put("canEncrypt", encryptOk);
        gpg.put("canSign", decryptOk);
        gpg.put("canEncryptSign", decryptOk);
        gpg.put("canDecrypt", decryptOk);
        gpg.put("canDecryptVerify", decryptOk);
        gpg.put("canVerify", decryptOk);
        gpg.put("isPublicServerKeyGopengpgCompatible", true);
        gpg.put("isPrivateServerKeyGopengpgCompatible", true);
        return gpg;
    }

    private Map<String, Object> checkApplication() {
        Map<String, Object> application = new LinkedHashMap<>();
        application.put("configPath", "classpath:application.yml");
        Map<String, Object> info = new LinkedHashMap<>();
        // No outbound HTTP version check: remoteVersion mirrors currentVersion.
        info.put("remoteVersion", currentVersion);
        info.put("currentVersion", currentVersion);
        application.put("info", info);
        application.put("latestVersion", true);
        application.put("sslForce", false);
        application.put("sslFullBaseUrl", false);
        application.put("seleniumDisabled", true);
        application.put("robotsIndexDisabled", true);
        // LinkedHashMap (not Map.of): selfRegistrationProvider is a nullable string.
        Map<String, Object> registrationClosed = new LinkedHashMap<>();
        registrationClosed.put("isSelfRegistrationPluginEnabled", false);
        registrationClosed.put("selfRegistrationProvider", null);
        registrationClosed.put("isRegistrationPublicRemovedFromPassbolt", true);
        application.put("registrationClosed", registrationClosed);
        application.put("hostAvailabilityCheckEnabled", false);
        application.put("jsProd", true);
        application.put("emailNotificationEnabled", false);
        application.put("schema", true);
        return application;
    }

    private Map<String, Object> checkDatabase() {
        boolean connect = false;
        int tablesCount = 0;
        // try-with-resources: the connection must be returned to the Hikari pool.
        try (Connection connection = dataSource.getConnection()) {
            connect = connection.isValid(1);
            // DatabaseMetaData works on both H2 (MODE=MySQL) and MySQL — do not
            // query information_schema directly (schema naming differs).
            try (ResultSet tables = connection.getMetaData()
                    .getTables(null, null, "%", new String[] { "TABLE" })) {
                while (tables.next()) {
                    tablesCount++;
                }
            }
        } catch (SQLException e) {
            log.warn("Healthcheck: database check failed", e);
        }
        boolean defaultContent = false;
        try {
            defaultContent = roleRepository.count() > 0;
        } catch (Exception e) {
            log.warn("Healthcheck: default content check failed", e);
        }
        Map<String, Object> database = new LinkedHashMap<>();
        database.put("supportedBackend", true);
        database.put("connect", connect);
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("tablesCount", tablesCount);
        database.put("info", info);
        database.put("tablesCount", tablesCount > 0);
        database.put("defaultContent", defaultContent);
        return database;
    }
}
