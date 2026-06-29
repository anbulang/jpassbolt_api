package com.jpassbolt.api.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jpassbolt.api.dto.SelfRegistrationDto;
import com.jpassbolt.api.exception.PassboltApiException;
import com.jpassbolt.api.model.OrganizationSetting;
import com.jpassbolt.api.repository.OrganizationSettingRepository;
import com.jpassbolt.api.repository.UserRepository;
import com.jpassbolt.api.service.email.event.SelfRegistrationSettingsChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Self-registration policy + guest sign-up gate — port of the PHP CE plugin
 * {@code Passbolt\SelfRegistration} (controllers
 * {@code SelfRegistrationGet/Set/DeleteSettingsController} +
 * {@code SelfRegistrationDryRunController}; services
 * {@code SelfRegistrationGet/Set/DeleteSettingsService} +
 * {@code SelfRegistrationEmailDomainsDryRunService}).
 *
 * <p>Like {@link EmailNotificationSettingsService}, the settings are NOT
 * entity-backed: they live as a single {@code organization_settings} row keyed by
 * {@code property = "selfRegistration"} (PHP
 * {@code USER_SELF_REGISTRATION_SETTINGS_PROPERTY_NAME}) whose {@code value} column
 * holds the JSON-serialized policy
 * {@code {"provider":"email_domains","data":{"allowed_domains":[...]}}}. No schema
 * change is required (iron rule #2).</p>
 *
 * <p>Two independent enable-states mirror PHP exactly: the plugin is always
 * compiled in here (so the {@code /self-registration/*} routes exist and the
 * {@code passbolt.plugins.selfRegistration.enabled} flag is advertised), but
 * registration is only OPEN ({@link #isOpen()}) once an admin has stored a
 * provider via {@link #save}. With no row, every {@link #canGuestSelfRegister}
 * attempt is rejected with {@code 403 "disabled"}.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SelfRegistrationService {

    /** PHP SelfRegistrationBaseSettingsService::USER_SELF_REGISTRATION_SETTINGS_PROPERTY_NAME. */
    public static final String ORG_SETTING_PROPERTY = "selfRegistration";

    /** The only valid provider (PHP SELF_REGISTRATION_EMAIL_DOMAINS — others are placeholders). */
    public static final String PROVIDER_EMAIL_DOMAINS = "email_domains";

    /** Seed for the deterministic property_id (mirrors EmailNotificationSettingsService). */
    private static final String ORG_PROPERTY_ID_SEED = "organization.setting.selfRegistration";

    /** Same single-@ email shape UserService.createUser enforces (closes the double-@ domain edge case). */
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final OrganizationSettingRepository organizationSettingRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    // ---------------------------------------------------------------------
    // settings (admin)
    // ---------------------------------------------------------------------

    /**
     * The effective self-registration settings, rendered for the API (PHP
     * {@code SelfRegistrationGetSettingsService::getSettings} +
     * {@code getRenderedValue}). When no row exists, returns the PHP default
     * {@code {provider:null, data:null}}. A corrupt/unreadable row also falls back
     * to that default (rather than 500ing — same defensive posture as the other
     * settings services), logging a warning.
     *
     * @return a non-null rendered settings map
     */
    @Transactional(readOnly = true)
    public Map<String, Object> get() {
        OrganizationSetting row = organizationSettingRepository.findByProperty(ORG_SETTING_PROPERTY)
                .orElse(null);
        if (row == null) {
            return defaultSettings();
        }
        Map<String, Object> stored = deserializeOrNull(row.getValue());
        if (stored == null) {
            log.warn("Unreadable selfRegistration organization setting — returning default (disabled).");
            return defaultSettings();
        }
        Map<String, Object> rendered = new LinkedHashMap<>();
        rendered.put("id", row.getId());
        rendered.put("provider", stored.get("provider"));
        rendered.put("data", stored.get("data"));
        rendered.put("created", row.getCreated());
        rendered.put("modified", row.getModified());
        rendered.put("created_by", row.getCreatedBy());
        rendered.put("modified_by", row.getModifiedBy());
        return rendered;
    }

    /**
     * Validate + persist the self-registration policy (PHP
     * {@code SelfRegistrationSetSettingsService::saveSettings}). Only the
     * {@code email_domains} provider is supported; {@code data.allowed_domains}
     * must be a non-empty list of valid bare domains. Upserts the single
     * {@code organization_settings} row, fires a settings-changed event, and
     * returns the freshly rendered settings.
     *
     * @param request the posted settings
     * @param adminId the acting admin id, stamped on the row
     * @return the new rendered settings map
     */
    @Transactional
    public Map<String, Object> save(SelfRegistrationDto.SettingsRequest request, String adminId) {
        if (request == null) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "The self registration settings could not be validated.");
        }
        String provider = request.getProvider();
        if (!PROVIDER_EMAIL_DOMAINS.equals(provider)) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "The self registration provider is not supported.");
        }
        List<String> domains = normalizeAndValidateDomains(
                request.getData() == null ? null : request.getData().getAllowedDomains());

        // Stored value shape: {"provider":"email_domains","data":{"allowed_domains":[...]}}
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("allowed_domains", domains);
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("provider", PROVIDER_EMAIL_DOMAINS);
        value.put("data", data);

        upsert(serialize(value), adminId);

        eventPublisher.publishEvent(new SelfRegistrationSettingsChangedEvent(
                PROVIDER_EMAIL_DOMAINS, domains, adminId));

        return get();
    }

    /**
     * Disable self-registration by deleting the settings row (PHP
     * {@code SelfRegistrationDeleteSettingsService}). The {@code id} must be a
     * UUID (400 otherwise) and must match the existing settings row (404 if no row
     * or a different id). Fires a settings-changed event with {@code provider=null}.
     *
     * @param id      the settings row id from the path
     * @param adminId the acting admin id
     * @return the rendered settings after deletion (the default, disabled shape)
     */
    @Transactional
    public Map<String, Object> delete(String id, String adminId) {
        if (id == null || !UUID_PATTERN.matcher(id).matches()) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "The self registration settings id should be a valid UUID.");
        }
        OrganizationSetting row = organizationSettingRepository.findByProperty(ORG_SETTING_PROPERTY)
                .filter(r -> id.equals(r.getId()))
                .orElseThrow(() -> new PassboltApiException(HttpStatus.NOT_FOUND,
                        "The self registration settings do not exist."));
        organizationSettingRepository.delete(row);

        eventPublisher.publishEvent(new SelfRegistrationSettingsChangedEvent(
                null, List.of(), adminId));

        return defaultSettings();
    }

    // ---------------------------------------------------------------------
    // guest gate (dry-run + register)
    // ---------------------------------------------------------------------

    /** True when an admin has configured a provider (PHP {@code isSelfRegistrationOpen}). */
    @Transactional(readOnly = true)
    public boolean isOpen() {
        return loadAllowedDomains() != null;
    }

    /**
     * The full guest self-registration gate, re-run by BOTH the dry-run endpoint
     * and the real register POST (PHP
     * {@code SelfRegistrationEmailDomainsDryRunService::canGuestSelfRegister}).
     * Creates nothing — it only throws when the guest may NOT register:
     * <ol>
     *   <li>missing/invalid email → 400 (PHP FormValidationException);</li>
     *   <li>self-registration disabled / no provider → 403 (PHP ForbiddenException);</li>
     *   <li>email domain not in the allow-list → 400 with a field-error body
     *       {@code {email:{checkEmailDomainIsAllowed:...}}} (PHP CustomValidationException,
     *       whose default code is 400 — verified against the PHP controller test);</li>
     *   <li>email already registered (non-deleted user) → 403 (PHP ForbiddenException).</li>
     * </ol>
     * A soft-deleted account's email is treated as available (PHP isUniqueUsername
     * counts deleted=false only); a disabled-but-not-deleted user still blocks it.
     *
     * @param email the candidate email (username)
     */
    @Transactional(readOnly = true)
    public void canGuestSelfRegister(String email) {
        String normalized = email == null ? null : email.trim().toLowerCase(Locale.ROOT);
        if (normalized == null || normalized.isBlank() || !EMAIL_PATTERN.matcher(normalized).matches()) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "The self registration data could not be validated.");
        }
        List<String> allowed = loadAllowedDomains();
        if (allowed == null) {
            throw new PassboltApiException(HttpStatus.FORBIDDEN,
                    "The self registration is disabled.");
        }
        if (!isDomainAllowed(normalized, allowed)) {
            // PHP CustomValidationException → HTTP 400 (its default code) with a
            // field-error body {email:{checkEmailDomainIsAllowed:...}}. The official
            // extension keys off the 400 + this body, so both are reproduced.
            throw new SelfRegistrationValidationException(
                    "The domain is not supported for self-registration.",
                    Map.of("email", Map.of("checkEmailDomainIsAllowed",
                            "The domain is not supported for self-registration.")));
        }
        if (userRepository.existsByUsernameAndDeletedFalse(normalized)) {
            throw new PassboltApiException(HttpStatus.FORBIDDEN,
                    "The email is already registered.");
        }
    }

    // ---------------------------------------------------------------------
    // internals
    // ---------------------------------------------------------------------

    private Map<String, Object> defaultSettings() {
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("provider", null);
        def.put("data", null);
        return def;
    }

    /**
     * The configured allow-list, or {@code null} when self-registration is
     * disabled (no row, unreadable row, no provider, or empty list). PHP
     * {@code getAllowedDomainsInSettings} treats a null/absent allowed_domains as
     * "disabled".
     */
    @SuppressWarnings("unchecked")
    private List<String> loadAllowedDomains() {
        OrganizationSetting row = organizationSettingRepository.findByProperty(ORG_SETTING_PROPERTY)
                .orElse(null);
        if (row == null) {
            return null;
        }
        Map<String, Object> stored = deserializeOrNull(row.getValue());
        if (stored == null || !PROVIDER_EMAIL_DOMAINS.equals(stored.get("provider"))) {
            return null;
        }
        Object dataObj = stored.get("data");
        if (!(dataObj instanceof Map)) {
            return null;
        }
        Object domainsObj = ((Map<String, Object>) dataObj).get("allowed_domains");
        if (!(domainsObj instanceof List)) {
            return null;
        }
        List<String> domains = new ArrayList<>();
        for (Object o : (List<Object>) domainsObj) {
            if (o != null && !o.toString().isBlank()) {
                domains.add(o.toString().trim());
            }
        }
        return domains.isEmpty() ? null : domains;
    }

    /**
     * Domain allow-list match (PHP {@code checkEmailDomainIsAllowed}): take the
     * substring after the (single) {@code @}, compare case-insensitively against
     * each allowed domain with exact whole-string equality (no subdomain
     * wildcarding — {@code mail.example.com} is rejected if only
     * {@code example.com} is listed). Case-insensitive matches PHP's default
     * {@code username.caseSensitive=false}.
     */
    private boolean isDomainAllowed(String normalizedEmail, List<String> allowedDomains) {
        int at = normalizedEmail.indexOf('@');
        if (at < 0 || at == normalizedEmail.length() - 1) {
            return false;
        }
        String domain = normalizedEmail.substring(at + 1); // already lowercased
        return allowedDomains.stream()
                .map(d -> d.trim().toLowerCase(Locale.ROOT))
                .anyMatch(domain::equals);
    }

    /**
     * Trim, drop blanks, and validate each allowed domain (PHP
     * {@code SelfRegistrationEmailDomainsSettingsForm::areEmailDomainsValidRule}:
     * non-empty list, each {@code noreply@{domain}} a valid email). A bare domain
     * has no {@code @} and no scheme; {@code "@example.com"} and {@code "example"}
     * (no dot) are rejected. No MX deep-check (PHP's is optional/config-gated).
     */
    private List<String> normalizeAndValidateDomains(List<String> rawDomains) {
        if (rawDomains == null || rawDomains.isEmpty()) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "At least one allowed domain is required.");
        }
        List<String> domains = new ArrayList<>();
        for (String raw : rawDomains) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String domain = raw.trim();
            if (!EMAIL_PATTERN.matcher("noreply@" + domain).matches()) {
                throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                        "The allowed domain \"" + domain + "\" is not valid.");
            }
            domains.add(domain);
        }
        if (domains.isEmpty()) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "At least one allowed domain is required.");
        }
        return domains;
    }

    private Map<String, Object> deserializeOrNull(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            log.warn("Unreadable selfRegistration setting value: {}", e.getMessage());
            return null;
        }
    }

    private String serialize(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new PassboltApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "The self registration settings could not be saved.", e);
        }
    }

    private void upsert(String value, String userId) {
        OrganizationSetting setting = organizationSettingRepository.findByProperty(ORG_SETTING_PROPERTY)
                .orElseGet(OrganizationSetting::new);
        if (setting.getId() == null) {
            setting.setProperty(ORG_SETTING_PROPERTY);
            setting.setPropertyId(deterministicUuid(ORG_PROPERTY_ID_SEED));
            setting.setCreatedBy(userId);
        }
        setting.setValue(value);
        setting.setModifiedBy(userId);
        organizationSettingRepository.save(setting);
    }

    private static String deterministicUuid(String seed) {
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
    }

    /**
     * A guest-gate validation failure carrying a field-error map rendered as the
     * 400 response body — the port of PHP {@code CustomValidationException} (default
     * code 400) raised for the domain-not-allowed case. The public controllers catch
     * this to attach the {@code {email:{checkEmailDomainIsAllowed:...}}} body that the
     * official extension expects; the simpler 403/400 gate failures stay as
     * {@link PassboltApiException} (message only).
     */
    public static class SelfRegistrationValidationException extends RuntimeException {

        private final transient Map<String, Object> errors;

        public SelfRegistrationValidationException(String message, Map<String, Object> errors) {
            super(message);
            this.errors = errors;
        }

        public Map<String, Object> getErrors() {
            return errors;
        }
    }
}
