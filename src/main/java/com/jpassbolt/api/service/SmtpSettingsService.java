package com.jpassbolt.api.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jpassbolt.api.dto.SmtpSettingsDto;
import com.jpassbolt.api.model.OrganizationSetting;
import com.jpassbolt.api.repository.OrganizationSettingRepository;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * SMTP transport settings + test-email service — port of the PHP CE plugin
 * {@code Passbolt\SmtpSettings} (services {@code SmtpSettingsGet/Set/Get-In-Db}
 * and {@code SmtpSettingsTestEmail}; form {@code EmailConfigurationForm}).
 *
 * <p><b>Storage (no schema change — iron rule #2).</b> Like the other settings
 * services, the config lives in a single {@code organization_settings} row keyed
 * by {@code property = "smtp"}. UNLIKE the email-notification / self-registration
 * settings (which are not secret), the SMTP config contains a password, so —
 * exactly like PHP — the {@code value} column holds a <b>GPG-encrypted</b> JSON
 * blob. Encryption uses the server's own key via {@link GpgService} (encrypt to
 * the server public key, decrypt with the server private key); Bouncy Castle only
 * (iron rule #1), the same round-trip the healthcheck already exercises. Storing
 * an SMTP credential in cleartext in the DB would be a real downgrade for a
 * zero-knowledge product, so the secret is encrypted at rest here.</p>
 *
 * <p><b>Source precedence (DB &gt; env), mirroring PHP {@code readConfigInDbOrFile}.</b>
 * {@link #get()} returns the DB row when present (source {@code "db"}, with row
 * metadata); otherwise it falls back to the static {@code spring.mail.*} /
 * {@code jpassbolt.email.from} config (source {@code "env"}), or {@code "undefined"}
 * when nothing is configured. PHP's separate {@code "file"} source has no analogue
 * here (JPassbolt has no {@code passbolt.php}), so file/env collapse to env.</p>
 *
 * <p><b>Runtime injection.</b> {@link #activeDbMailSender()} / {@link #activeDbFrom()}
 * are the JPassbolt equivalent of PHP's {@code SmtpTransportBeforeSendEventListener}:
 * {@code MailService.send()} consults them so a DB-configured SMTP server overrides
 * the {@code spring.mail.*} bean at send time, falling back to it when no row exists.</p>
 *
 * <p>The GET response returns the password in cleartext to the admin caller —
 * faithful to PHP (admin-only; the official admin UI pre-fills the form). The only
 * masking is in the test-email SMTP {@code debug} trace.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SmtpSettingsService {

    /** PHP SmtpSettingsGetSettingsInDbService::SMTP_SETTINGS_PROPERTY_NAME. */
    public static final String ORG_SETTING_PROPERTY = "smtp";

    /** Seed for the deterministic property_id (mirrors the other settings services). */
    private static final String ORG_PROPERTY_ID_SEED = "organization.setting.smtp";

    /** PHP SmtpSettingsGetService source constants (no "file" source in JPassbolt). */
    public static final String SOURCE_DB = "db";
    public static final String SOURCE_ENV = "env";
    public static final String SOURCE_UNDEFINED = "undefined";

    /** The persisted/returned SMTP fields (PHP SMTP_SETTINGS_ALLOWED_FIELDS). */
    private static final List<String> ALLOWED_FIELDS = List.of(
            "sender_name", "sender_email", "host", "tls", "port", "client", "username", "password");

    /** Single-@ email shape (same as SelfRegistrationService / UserService). */
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    /** Loose IPv4 recognizer for the optional SMTP client (HELO) value. */
    private static final Pattern IPV4_PATTERN = Pattern.compile(
            "^(?:(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)$");

    /** Fast-fail timeouts so a misconfigured host never hangs a request/test. */
    private static final String SMTP_TIMEOUT_MS = "5000";

    private final OrganizationSettingRepository organizationSettingRepository;
    private final ObjectMapper objectMapper;
    private final GpgService gpgService;

    // env fallback (PHP file/env source) — the static spring.mail.* config + From.
    @Value("${spring.mail.host:}")
    private String envHost;

    @Value("${spring.mail.port:}")
    private String envPort;

    @Value("${spring.mail.username:}")
    private String envUsername;

    @Value("${spring.mail.password:}")
    private String envPassword;

    @Value("${jpassbolt.email.from:no-reply@jpassbolt.local}")
    private String envFrom;

    // ---------------------------------------------------------------------
    // settings (admin GET / POST|PUT)
    // ---------------------------------------------------------------------

    /**
     * The effective SMTP settings rendered for the API (PHP
     * {@code SmtpSettingsGetService::getSettings}). DB row first (source
     * {@code "db"} + id/timestamps), else the static env config (source
     * {@code "env"}), else {@code "undefined"}. An unreadable/undecryptable row
     * logs a warning and falls back to env (more graceful than PHP's 500; the
     * healthcheck still reports the row exists).
     */
    @Transactional(readOnly = true)
    public Map<String, Object> get() {
        OrganizationSetting row = organizationSettingRepository.findByProperty(ORG_SETTING_PROPERTY)
                .orElse(null);
        if (row != null) {
            Map<String, Object> stored = decryptAndParse(row.getValue());
            if (stored != null) {
                Map<String, Object> rendered = new LinkedHashMap<>();
                for (String field : ALLOWED_FIELDS) {
                    rendered.put(field, stored.get(field));
                }
                rendered.put("source", SOURCE_DB);
                rendered.put("id", row.getId());
                rendered.put("created", row.getCreated());
                rendered.put("modified", row.getModified());
                rendered.put("created_by", row.getCreatedBy());
                rendered.put("modified_by", row.getModifiedBy());
                return rendered;
            }
            log.warn("Unreadable/undecryptable smtp organization setting — falling back to env config.");
        }
        return envSettings();
    }

    /**
     * Validate + GPG-encrypt + persist the SMTP config (PHP
     * {@code SmtpSettingsSetService::saveSettings}). Upserts the single
     * {@code organization_settings} row and returns the freshly rendered settings
     * (source {@code "db"}).
     *
     * @param request the posted SMTP config
     * @param adminId the acting admin id, stamped on the row
     * @return the new rendered settings map (with source/id/timestamps)
     * @throws SmtpSettingsValidationException with a field-error body on invalid input
     */
    @Transactional
    public Map<String, Object> save(SmtpSettingsDto.SettingsRequest request, String adminId) {
        Map<String, Object> errors = new LinkedHashMap<>();
        SmtpSettingsDto.SettingsRequest req = request == null
                ? new SmtpSettingsDto.SettingsRequest() : request;

        String senderName = requireText(errors, "sender_name", req.getSenderName(),
                "A sender name is required.");
        String senderEmail = requireEmail(errors, "sender_email", req.getSenderEmail(),
                "A sender email is required.", "The sender email should be a valid email address.");
        String host = requireText(errors, "host", req.getHost(), "A host name is required.");
        Boolean tls = normalizeTls(req.getTls());
        Integer port = normalizePort(errors, req.getPort());
        String client = normalizeClient(errors, req.getClient());

        if (!errors.isEmpty()) {
            throw new SmtpSettingsValidationException("Could not validate the smtp settings.", errors);
        }

        Map<String, Object> value = new LinkedHashMap<>();
        value.put("sender_name", senderName);
        value.put("sender_email", senderEmail);
        value.put("host", host);
        value.put("tls", tls);
        value.put("port", port);
        value.put("client", client);
        value.put("username", blankToNull(req.getUsername()));
        value.put("password", emptyableSecret(req.getPassword()));

        upsert(encrypt(serialize(value)), adminId);
        return get();
    }

    // ---------------------------------------------------------------------
    // runtime injection (PHP SmtpTransportBeforeSendEventListener)
    // ---------------------------------------------------------------------

    /** True when an SMTP config row exists (PHP {@code source === 'db'} / isInDb). */
    @Transactional(readOnly = true)
    public boolean isInDb() {
        return organizationSettingRepository.findByProperty(ORG_SETTING_PROPERTY).isPresent();
    }

    /** The resolved source label for the healthcheck (db &gt; env &gt; undefined). */
    @Transactional(readOnly = true)
    public String currentSource() {
        if (isInDb()) {
            return SOURCE_DB;
        }
        return (envHost != null && !envHost.isBlank()) ? SOURCE_ENV : SOURCE_UNDEFINED;
    }

    /**
     * A {@link JavaMailSender} built from the DB SMTP config, or empty when no
     * (decryptable) row exists — the caller then falls back to the
     * {@code spring.mail.*} bean. Used by {@code MailService.send()}.
     */
    @Transactional(readOnly = true)
    public Optional<JavaMailSender> activeDbMailSender() {
        return getDbSettings().map(s -> buildSender(
                str(s.get("host")), intVal(s.get("port")),
                str(s.get("username")), str(s.get("password")), truthy(s.get("tls"))));
    }

    /** The DB sender address ({@code "Name <email>"}), or empty when no DB row. */
    @Transactional(readOnly = true)
    public Optional<String> activeDbFrom() {
        return getDbSettings().map(s -> formatFrom(str(s.get("sender_email")), str(s.get("sender_name"))));
    }

    /** The decrypted stored SMTP fields (the 8 fields, no metadata), or empty. */
    @Transactional(readOnly = true)
    public Optional<Map<String, Object>> getDbSettings() {
        OrganizationSetting row = organizationSettingRepository.findByProperty(ORG_SETTING_PROPERTY)
                .orElse(null);
        if (row == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(decryptAndParse(row.getValue()));
    }

    // ---------------------------------------------------------------------
    // test email (PHP SmtpSettingsEmailController + SmtpSettingsTestEmailService)
    // ---------------------------------------------------------------------

    /**
     * Send a test email through the SMTP config in the request (NOT the DB row —
     * PHP uses the posted settings so an admin can probe a config before saving).
     * Returns {@code {debug: [...]}} carrying the SMTP wire trace (credentials
     * masked) on success.
     *
     * @throws SmtpSettingsValidationException with a field-error body on invalid input
     * @throws SmtpTestEmailException          with a {@code {debug}} body when delivery fails
     */
    public Map<String, Object> sendTestEmail(SmtpSettingsDto.TestEmailRequest request) {
        Map<String, Object> errors = new LinkedHashMap<>();
        SmtpSettingsDto.TestEmailRequest req = request == null
                ? new SmtpSettingsDto.TestEmailRequest() : request;

        String senderEmail = requireEmail(errors, "sender_email", req.getSenderEmail(),
                "A sender email is required.", "The sender email should be a valid email address.");
        String senderName = blankToNull(req.getSenderName());
        String host = requireText(errors, "host", req.getHost(), "A host name is required.");
        Boolean tls = normalizeTls(req.getTls());
        Integer port = normalizePort(errors, req.getPort());
        String testTo = requireEmail(errors, "email_test_to", req.getEmailTestTo(),
                "A test recipient is required.", "The test email should be a valid email address.");
        String username = blankToNull(req.getUsername());
        String password = req.getPassword();

        if (!errors.isEmpty()) {
            throw new SmtpSettingsValidationException("Could not validate the smtp settings.", errors);
        }

        JavaMailSenderImpl sender = buildSender(host, port, username, password, Boolean.TRUE.equals(tls));
        ByteArrayOutputStream traceBuffer = new ByteArrayOutputStream();
        PrintStream tracer = new PrintStream(traceBuffer, true, StandardCharsets.UTF_8);
        sender.getSession().setDebugOut(tracer);
        sender.getSession().setDebug(true);

        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(formatFrom(senderEmail, senderName));
            helper.setTo(testTo);
            helper.setSubject("JPassbolt test email");
            helper.setText("Congratulations!\nIf you receive this email, it means that your "
                    + "JPassbolt SMTP configuration is working fine.", false);
            sender.send(message);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("debug", maskTrace(traceBuffer.toString(StandardCharsets.UTF_8), username, password));
            return body;
        } catch (Exception e) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("debug", maskTrace(traceBuffer.toString(StandardCharsets.UTF_8), username, password));
            throw new SmtpTestEmailException(
                    e.getMessage() == null ? "The test email could not be sent." : e.getMessage(), body);
        }
    }

    // ---------------------------------------------------------------------
    // internals — rendering / fallback
    // ---------------------------------------------------------------------

    /** The env (spring.mail.* + jpassbolt.email.from) fallback render, or all-null undefined. */
    private Map<String, Object> envSettings() {
        Map<String, Object> rendered = new LinkedHashMap<>();
        boolean haveEnv = envHost != null && !envHost.isBlank();
        rendered.put("sender_name", null);
        rendered.put("sender_email", haveEnv ? envFrom : null);
        rendered.put("host", haveEnv ? envHost : null);
        rendered.put("tls", null);
        rendered.put("port", haveEnv ? parseIntOrNull(envPort) : null);
        rendered.put("client", null);
        rendered.put("username", haveEnv ? blankToNull(envUsername) : null);
        rendered.put("password", haveEnv ? blankToNull(envPassword) : null);
        rendered.put("source", haveEnv ? SOURCE_ENV : SOURCE_UNDEFINED);
        return rendered;
    }

    // ---------------------------------------------------------------------
    // internals — transport
    // ---------------------------------------------------------------------

    private JavaMailSenderImpl buildSender(String host, Integer port, String username,
            String password, boolean tls) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(host);
        if (port != null) {
            sender.setPort(port);
        }
        boolean auth = username != null && !username.isBlank();
        if (auth) {
            sender.setUsername(username);
            sender.setPassword(password);
        }
        sender.setDefaultEncoding("UTF-8");
        Properties props = sender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", String.valueOf(auth));
        props.put("mail.smtp.starttls.enable", String.valueOf(tls));
        props.put("mail.smtp.connectiontimeout", SMTP_TIMEOUT_MS);
        props.put("mail.smtp.timeout", SMTP_TIMEOUT_MS);
        props.put("mail.smtp.writetimeout", SMTP_TIMEOUT_MS);
        return sender;
    }

    private String formatFrom(String email, String name) {
        if (name != null && !name.isBlank()) {
            return name + " <" + email + ">";
        }
        return email;
    }

    /**
     * Mask SMTP credentials in the captured debug trace (PHP
     * {@code SmtpSettingsTestEmailService::removeCredentials}): the raw
     * username/password plus their Base64 forms (AUTH LOGIN) and the
     * {@code \0user\0pass} AUTH PLAIN blob are all replaced with {@code *****}.
     */
    private List<String> maskTrace(String trace, String username, String password) {
        if (trace == null || trace.isEmpty()) {
            return List.of();
        }
        String masked = trace;
        Base64.Encoder b64 = Base64.getEncoder();
        for (String secret : new String[] { password, username }) {
            if (secret == null || secret.isEmpty()) {
                continue;
            }
            masked = masked.replace(secret, "*****");
            masked = masked.replace(b64.encodeToString(secret.getBytes(StandardCharsets.UTF_8)), "*****");
        }
        if (username != null && !username.isEmpty()) {
            String authPlain = b64.encodeToString(
                    ("\0" + username + "\0" + (password == null ? "" : password))
                            .getBytes(StandardCharsets.UTF_8));
            masked = masked.replace(authPlain, "*****");
        }
        return new ArrayList<>(Arrays.asList(masked.split("\\r?\\n")));
    }

    // ---------------------------------------------------------------------
    // internals — validation / normalization (PHP EmailConfigurationForm)
    // ---------------------------------------------------------------------

    private String requireText(Map<String, Object> errors, String field, String value, String requiredMsg) {
        String trimmed = value == null ? null : value.trim();
        if (trimmed == null || trimmed.isEmpty()) {
            putError(errors, field, "_required", requiredMsg);
            return null;
        }
        return trimmed;
    }

    private String requireEmail(Map<String, Object> errors, String field, String value,
            String requiredMsg, String emailMsg) {
        String trimmed = value == null ? null : value.trim();
        if (trimmed == null || trimmed.isEmpty()) {
            putError(errors, field, "_required", requiredMsg);
            return null;
        }
        if (!EMAIL_PATTERN.matcher(trimmed).matches()) {
            putError(errors, field, "email", emailMsg);
            return null;
        }
        return trimmed;
    }

    /** PHP mapTlsToTrueOrNull: filter_var(BOOLEAN) → TRUE, else null. */
    private Boolean normalizeTls(Object tls) {
        return truthy(tls) ? Boolean.TRUE : null;
    }

    /** PHP port rule: numeric, range [1,65535]; accepts a number or numeric string. */
    private Integer normalizePort(Map<String, Object> errors, Object port) {
        if (port == null || (port instanceof String s && s.isBlank())) {
            putError(errors, "port", "_required", "A port number is required.");
            return null;
        }
        long value;
        if (port instanceof Number n) {
            value = n.longValue();
        } else {
            try {
                value = Long.parseLong(port.toString().trim());
            } catch (NumberFormatException e) {
                putError(errors, "port", "integer", "The port number should be numeric.");
                return null;
            }
        }
        if (value < 1 || value > 65535) {
            putError(errors, "port", "range", "The port number should be between 1 and 65535.");
            return null;
        }
        return (int) value;
    }

    /** PHP setClient + SmtpSettingsClientValidationRule: empty → null; else valid IP or domain. */
    private String normalizeClient(Map<String, Object> errors, String client) {
        if (client == null || client.isBlank()) {
            return null;
        }
        String trimmed = client.trim();
        boolean valid = IPV4_PATTERN.matcher(trimmed).matches()
                || trimmed.contains(":") // loose IPv6
                || EMAIL_PATTERN.matcher("noreply@" + trimmed).matches();
        if (!valid) {
            putError(errors, "client", "isClientValid", "The client should be a valid IP or a valid domain.");
            return null;
        }
        return trimmed;
    }

    @SuppressWarnings("unchecked")
    private void putError(Map<String, Object> errors, String field, String rule, String message) {
        ((Map<String, Object>) errors.computeIfAbsent(field, k -> new LinkedHashMap<String, Object>()))
                .put(rule, message);
    }

    // ---------------------------------------------------------------------
    // internals — persistence (encrypt at rest via the server key)
    // ---------------------------------------------------------------------

    private void upsert(String encryptedValue, String userId) {
        OrganizationSetting setting = organizationSettingRepository.findByProperty(ORG_SETTING_PROPERTY)
                .orElseGet(OrganizationSetting::new);
        if (setting.getId() == null) {
            setting.setProperty(ORG_SETTING_PROPERTY);
            setting.setPropertyId(deterministicUuid(ORG_PROPERTY_ID_SEED));
            setting.setCreatedBy(userId);
        }
        setting.setValue(encryptedValue);
        setting.setModifiedBy(userId);
        organizationSettingRepository.save(setting);
    }

    /** Encrypt the JSON to the server's own public key (decryptable by the server private key). */
    private String encrypt(String json) {
        return gpgService.encrypt(json, gpgService.getServerPublicKey());
    }

    private Map<String, Object> decryptAndParse(String encryptedValue) {
        if (encryptedValue == null || encryptedValue.isBlank()) {
            return null;
        }
        try {
            String json = gpgService.decrypt(encryptedValue);
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            log.warn("Could not decrypt/parse stored smtp settings: {}", e.getMessage());
            return null;
        }
    }

    private String serialize(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new SmtpSettingsValidationException("The smtp settings could not be saved.",
                    new LinkedHashMap<>());
        }
    }

    private static String deterministicUuid(String seed) {
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
    }

    // ---------------------------------------------------------------------
    // internals — small coercions
    // ---------------------------------------------------------------------

    private static boolean truthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.intValue() != 0;
        }
        String s = value.toString().trim().toLowerCase(java.util.Locale.ROOT);
        return s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("on");
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }

    private static Integer intVal(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        return parseIntOrNull(value == null ? null : value.toString());
    }

    private static Integer parseIntOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    /** Password may legitimately be an empty string (no-auth); keep null for blank-or-null. */
    private static String emptyableSecret(String value) {
        return (value == null || value.isEmpty()) ? null : value;
    }

    // ---------------------------------------------------------------------
    // exceptions (rendered as 400 by the controller)
    // ---------------------------------------------------------------------

    /** Validation failure carrying a {field:{rule:message}} body (PHP FormValidationException → 400). */
    public static class SmtpSettingsValidationException extends RuntimeException {

        private final transient Map<String, Object> errors;

        public SmtpSettingsValidationException(String message, Map<String, Object> errors) {
            super(message);
            this.errors = errors;
        }

        public Map<String, Object> getErrors() {
            return errors;
        }
    }

    /** Test-email delivery failure carrying a {@code {debug}} trace body (PHP error(...,400)). */
    public static class SmtpTestEmailException extends RuntimeException {

        private final transient Map<String, Object> body;

        public SmtpTestEmailException(String message, Map<String, Object> body) {
            super(message);
            this.body = body;
        }

        public Map<String, Object> getBody() {
            return body;
        }
    }
}
