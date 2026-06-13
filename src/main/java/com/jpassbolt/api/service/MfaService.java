package com.jpassbolt.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jpassbolt.api.config.SettingsProperties;
import com.jpassbolt.api.model.AccountSetting;
import com.jpassbolt.api.model.AuthenticationToken;
import com.jpassbolt.api.model.OrganizationSetting;
import com.jpassbolt.api.repository.AccountSettingRepository;
import com.jpassbolt.api.repository.AuthenticationTokenRepository;
import com.jpassbolt.api.repository.OrganizationSettingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * MFA business orchestration: organization/account level MFA settings,
 * provider resolution, verified-token lifecycle and TOTP form validation.
 *
 * <p>
 * Ported from the PHP plugin utilities {@code MfaSettings},
 * {@code MfaAccountSettings}, {@code MfaOrgSettings},
 * {@code MfaVerifiedToken} and {@code IsMfaAuthenticationRequiredService}.
 * </p>
 *
 * <p>
 * Deliberate deviation from PHP (documented in the porting blueprint): PHP
 * binds the MFA verified token to a hashed session id (and the JWT access
 * token in JWT mode, see {@code hashAndSetSessionId}). Java simplifies this
 * to "active + created within 30 days" with no session binding — slightly
 * weaker (a stolen cookie stays usable across sessions for up to 30 days).
 * TODO: bind to the refresh-token session once the auth-extras cluster lands.
 * The MFA verified state lives ONLY in the {@code passbolt_mfa} cookie +
 * {@code authentication_tokens} rows, never as a JWT claim (same as the
 * official implementation). {@link #getEnabledProviders(String)} is the
 * integration surface for the JWT login challenge ("providers" hint).
 * </p>
 *
 * <p>
 * TODO (security debt, also flagged in the blueprint): PHP throttles failed
 * MFA attempts via {@code MfaRateLimiterService} backed by the
 * {@code action_logs} table, which this project does not have. Until a rate
 * limiter is added, 6-digit codes are brute-forceable in theory.
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MfaService {

    public static final String MFA_PROPERTY = "mfa";
    public static final String PROVIDER_TOTP = "totp";
    public static final String MFA_TOKEN_TYPE = "mfa";
    /** PHP MfaVerifiedToken::MAX_DURATION ("30 days"). */
    public static final int MFA_TOKEN_MAX_DURATION_DAYS = 30;

    private static final String ACCOUNT_PROPERTY_ID_SEED = "account.setting.mfa";
    private static final String ORGANIZATION_PROPERTY_ID_SEED = "organization.setting.mfa";

    private final AccountSettingRepository accountSettingRepository;
    private final OrganizationSettingRepository organizationSettingRepository;
    private final AuthenticationTokenRepository authenticationTokenRepository;
    private final TotpService totpService;
    private final ObjectMapper objectMapper;
    private final SettingsProperties settingsProperties;

    // ------------------------------------------------------------------
    // Settings resolution (PHP MfaSettings / MfaOrgSettings)
    // ------------------------------------------------------------------

    /**
     * Providers enabled at the organization level
     * (organization_settings property='mfa', value {"providers":[...]}).
     * Empty list when the setting row does not exist.
     */
    public List<String> getOrgEnabledProviders() {
        return organizationSettingRepository.findByProperty(MFA_PROPERTY)
                .map(setting -> readProviders(readTreeSafely(setting.getValue())))
                .orElse(List.of());
    }

    /**
     * Raw account-level MFA settings JSON for a user, if any.
     */
    public Optional<JsonNode> getAccountMfaSettings(String userId) {
        return accountSettingRepository.findFirstByUserIdAndProperty(userId, MFA_PROPERTY)
                .map(setting -> readTreeSafely(setting.getValue()));
    }

    /**
     * True when the user has an account_settings row for the mfa property
     * (whether or not any provider in it is ready).
     */
    public boolean hasAccountMfaSettings(String userId) {
        return accountSettingRepository.findFirstByUserIdAndProperty(userId, MFA_PROPERTY).isPresent();
    }

    /**
     * Providers effectively enabled for a user: the intersection of the
     * organization-enabled providers and the providers the user has fully
     * configured ("ready", PHP MfaSettings::getEnabledProviders).
     */
    public List<String> getEnabledProviders(String userId) {
        List<String> orgProviders = getOrgEnabledProviders();
        if (orgProviders.isEmpty()) {
            return List.of();
        }
        Optional<JsonNode> accountSettings = getAccountMfaSettings(userId);
        if (accountSettings.isEmpty()) {
            return List.of();
        }
        List<String> enabled = new ArrayList<>();
        for (String provider : orgProviders) {
            if (isProviderReady(accountSettings.get(), provider)) {
                enabled.add(provider);
            }
        }
        return enabled;
    }

    /**
     * True if the user's TOTP setup is complete and usable
     * (PHP MfaAccountSettings::isProviderReady for totp).
     */
    public boolean isTotpProviderReady(String userId) {
        return getAccountMfaSettings(userId)
                .map(settings -> isProviderReady(settings, PROVIDER_TOTP))
                .orElse(false);
    }

    /**
     * The verified timestamp of the user's TOTP setup, only when the
     * provider is ready.
     */
    public Optional<String> getTotpVerifiedTime(String userId) {
        return getAccountMfaSettings(userId)
                .filter(settings -> isProviderReady(settings, PROVIDER_TOTP))
                .map(settings -> settings.path(PROVIDER_TOTP).path("verified").asText());
    }

    /**
     * The stored otpProvisioningUri of the user's TOTP setup, if any.
     */
    public Optional<String> getTotpProvisioningUri(String userId) {
        return getAccountMfaSettings(userId)
                .map(settings -> settings.path(PROVIDER_TOTP).path("otpProvisioningUri"))
                .filter(JsonNode::isTextual)
                .map(JsonNode::asText);
    }

    // ------------------------------------------------------------------
    // MFA check / verified token (PHP IsMfaAuthenticationRequiredService,
    // MfaVerifiedToken)
    // ------------------------------------------------------------------

    /**
     * Whether the current request must be blocked pending MFA verification:
     * the user has at least one enabled provider and carries no valid
     * passbolt_mfa cookie token.
     */
    @Transactional
    public boolean isMfaCheckRequired(String userId, String mfaCookieToken) {
        if (getEnabledProviders(userId).isEmpty()) {
            return false;
        }
        return mfaCookieToken == null || !isMfaTokenValid(userId, mfaCookieToken);
    }

    /**
     * Validate an MFA verified token: active, type 'mfa', belonging to the
     * user and created within the last 30 days. An invalid (expired or
     * mismatched) mfa token found in the table is deactivated on the way out
     * (PHP MfaVerifiedToken::check, minus the session id binding). Tokens of
     * other types (login/register/...) are never touched.
     */
    @Transactional
    public boolean isMfaTokenValid(String userId, String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        Optional<AuthenticationToken> found = authenticationTokenRepository.findByToken(token);
        if (found.isEmpty()) {
            return false;
        }
        AuthenticationToken authToken = found.get();
        if (!MFA_TOKEN_TYPE.equals(authToken.getType())) {
            // Never touch tokens of other types (login/register/...)
            return false;
        }
        boolean valid = Boolean.TRUE.equals(authToken.getActive())
                && userId.equals(authToken.getUserId())
                && authToken.getCreated() != null
                && authToken.getCreated().isAfter(LocalDateTime.now().minusDays(MFA_TOKEN_MAX_DURATION_DAYS));
        if (!valid && Boolean.TRUE.equals(authToken.getActive())) {
            authToken.setActive(false);
            authenticationTokenRepository.save(authToken);
        }
        return valid;
    }

    /**
     * Create an MFA verified token (PHP MfaVerifiedToken::get):
     * authentication_tokens row with type 'mfa' and a JSON data payload
     * {provider, user_agent, remember}.
     *
     * @return the token UUID, to be set as the passbolt_mfa cookie value
     */
    @Transactional
    public String createMfaVerifiedToken(String userId, String provider, boolean remember, String userAgent) {
        ObjectNode data = objectMapper.createObjectNode();
        data.put("provider", provider);
        data.put("user_agent", userAgent != null ? userAgent : "unknown");
        data.put("remember", remember);

        AuthenticationToken token = new AuthenticationToken();
        token.setUserId(userId);
        token.setToken(UUID.randomUUID().toString());
        token.setType(MFA_TOKEN_TYPE);
        token.setActive(true);
        token.setData(data.toString());
        authenticationTokenRepository.save(token);
        return token.getToken();
    }

    /**
     * Deactivate every mfa token of a user
     * (PHP MfaVerifiedToken::setAllInactive). Rows are kept, only flipped;
     * tokens of other types are untouched (the lookup filters on type).
     */
    @Transactional
    public void invalidateAllMfaTokens(String userId) {
        List<AuthenticationToken> tokens = authenticationTokenRepository
                .findByUserIdAndType(userId, MFA_TOKEN_TYPE);
        for (AuthenticationToken token : tokens) {
            token.setActive(false);
        }
        authenticationTokenRepository.saveAll(tokens);
    }

    // ------------------------------------------------------------------
    // TOTP verify form validation (PHP TotpVerifyForm)
    // ------------------------------------------------------------------

    /**
     * Validate a submitted TOTP code against the user's stored provisioning
     * URI. Rules short-circuit in the PHP TotpVerifyForm order
     * (_required → _empty → numeric → minLength → isValidOtp).
     *
     * @return ordered map of {rule: message} errors, empty when valid
     */
    public Map<String, String> validateTotpCode(String userId, String totp) {
        return validateTotp(getTotpProvisioningUri(userId).orElse(null), totp);
    }

    /**
     * Same rules as {@link #validateTotpCode(String, String)} but against an
     * explicit provisioning URI — used by the TOTP setup flow where the URI
     * comes from the request, not from the stored account settings (PHP
     * TotpSetupForm).
     */
    public Map<String, String> validateTotpCodeAgainstUri(String provisioningUri, String totp) {
        return validateTotp(provisioningUri, totp);
    }

    private Map<String, String> validateTotp(String provisioningUri, String totp) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (totp == null) {
            errors.put("_required", "An OTP is required.");
            return errors;
        }
        if (totp.isEmpty()) {
            errors.put("_empty", "The OTP should not be empty.");
            return errors;
        }
        if (!totp.chars().allMatch(Character::isDigit)) {
            errors.put("numeric", "The OTP should be composed of numbers only.");
            return errors;
        }
        if (totp.length() < 6) {
            errors.put("minLength", "The OTP should be at least 6 characters long.");
            return errors;
        }
        boolean valid = false;
        if (provisioningUri != null) {
            try {
                valid = totpService.verifyCode(provisioningUri, totp);
            } catch (IllegalArgumentException e) {
                log.warn("otpProvisioningUri used for TOTP validation is not parsable");
            }
        }
        if (!valid) {
            errors.put("isValidOtp", "This OTP is not valid.");
        }
        return errors;
    }

    // ------------------------------------------------------------------
    // TOTP setup (PHP MfaOtpFactory / MfaAccountSettings)
    // ------------------------------------------------------------------

    /**
     * Generate a fresh provisioning URI for a setup attempt. Nothing is
     * persisted — like PHP, the client posts the URI back together with a
     * matching code.
     */
    public String generateTotpSetupUri(String username) {
        String secret = totpService.generateSecret();
        return totpService.buildProvisioningUri(deriveIssuer(), username, secret);
    }

    /**
     * Persist (upsert) the user's TOTP configuration
     * (PHP MfaAccountSettings::enableProvider): merge 'totp' into the
     * providers array and store {otpProvisioningUri, verified} under 'totp'.
     *
     * @return the verified timestamp written into the settings
     */
    @Transactional
    public LocalDateTime enableTotpProvider(String userId, String otpProvisioningUri) {
        LocalDateTime verified = LocalDateTime.now();
        Optional<AccountSetting> existing = accountSettingRepository.findFirstByUserIdAndProperty(userId, MFA_PROPERTY);

        ObjectNode root = existing
                .map(setting -> readTreeSafely(setting.getValue()))
                .filter(JsonNode::isObject)
                .map(node -> (ObjectNode) node)
                .orElseGet(objectMapper::createObjectNode);

        ArrayNode providers = ensureProvidersArray(root);
        if (!containsText(providers, PROVIDER_TOTP)) {
            providers.add(PROVIDER_TOTP);
        }
        ObjectNode totp = root.putObject(PROVIDER_TOTP);
        totp.put("otpProvisioningUri", otpProvisioningUri);
        totp.put("verified", verified.toString());

        AccountSetting setting = existing.orElseGet(() -> {
            AccountSetting created = new AccountSetting();
            created.setUserId(userId);
            created.setProperty(MFA_PROPERTY);
            created.setPropertyId(deterministicUuid(ACCOUNT_PROPERTY_ID_SEED));
            return created;
        });
        setting.setValue(root.toString());
        accountSettingRepository.save(setting);
        return verified;
    }

    /**
     * Remove the user's TOTP configuration
     * (PHP MfaAccountSettings::disableProvider): drop 'totp' from the
     * providers array; when no provider remains, physically delete the
     * account_settings row (the table has no deleted column — PHP does the
     * same) and deactivate all mfa tokens of the user.
     *
     * @return true when a configuration existed, false when there was
     *         nothing to delete (idempotent delete semantics)
     */
    @Transactional
    public boolean disableTotpProvider(String userId) {
        Optional<AccountSetting> existing = accountSettingRepository.findFirstByUserIdAndProperty(userId, MFA_PROPERTY);
        if (existing.isEmpty()) {
            return false;
        }
        AccountSetting setting = existing.get();
        JsonNode parsed = readTreeSafely(setting.getValue());
        ObjectNode root = parsed.isObject() ? (ObjectNode) parsed : objectMapper.createObjectNode();

        ArrayNode providers = ensureProvidersArray(root);
        ArrayNode remaining = objectMapper.createArrayNode();
        for (JsonNode provider : providers) {
            if (!PROVIDER_TOTP.equals(provider.asText())) {
                remaining.add(provider.asText());
            }
        }
        root.set("providers", remaining);
        root.remove(PROVIDER_TOTP);

        if (remaining.isEmpty()) {
            accountSettingRepository.delete(setting);
            invalidateAllMfaTokens(userId);
        } else {
            setting.setValue(root.toString());
            accountSettingRepository.save(setting);
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Organization settings (PHP MfaOrgSettings)
    // ------------------------------------------------------------------

    /**
     * The organization-level MFA configuration, defaulting to
     * {"providers":[]} when the setting row does not exist.
     */
    public Map<String, Object> getOrgMfaConfig() {
        return organizationSettingRepository.findByProperty(MFA_PROPERTY)
                .map(setting -> {
                    try {
                        return objectMapper.readValue(setting.getValue(),
                                new TypeReference<Map<String, Object>>() {
                                });
                    } catch (JsonProcessingException e) {
                        log.warn("Unparsable organization mfa settings value, falling back to defaults");
                        return defaultOrgConfig();
                    }
                })
                .orElseGet(this::defaultOrgConfig);
    }

    /**
     * Upsert the organization-level MFA configuration. The providers list is
     * validated by the controller; this method only persists it. Both
     * created_by and modified_by are NOT NULL in the official schema, hence
     * they are always set here (create sets both, update refreshes
     * modified_by).
     */
    @Transactional
    public Map<String, Object> setOrgMfaConfig(List<String> providers, String adminUserId) {
        OrganizationSetting setting = organizationSettingRepository.findByProperty(MFA_PROPERTY)
                .orElseGet(() -> {
                    OrganizationSetting created = new OrganizationSetting();
                    created.setProperty(MFA_PROPERTY);
                    created.setPropertyId(deterministicUuid(ORGANIZATION_PROPERTY_ID_SEED));
                    created.setCreatedBy(adminUserId);
                    return created;
                });
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode providersNode = root.putArray("providers");
        providers.forEach(providersNode::add);
        setting.setValue(root.toString());
        setting.setModifiedBy(adminUserId);
        organizationSettingRepository.save(setting);

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("providers", new ArrayList<>(providers));
        return config;
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private boolean isProviderReady(JsonNode accountSettings, String provider) {
        if (!readProviders(accountSettings).contains(provider)) {
            return false;
        }
        if (PROVIDER_TOTP.equals(provider)) {
            JsonNode totp = accountSettings.path(PROVIDER_TOTP);
            return totp.hasNonNull("verified") && totp.hasNonNull("otpProvisioningUri");
        }
        // yubikey/duo are not implemented and can never be ready here
        return false;
    }

    private List<String> readProviders(JsonNode node) {
        JsonNode providers = node.path("providers");
        if (!providers.isArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (JsonNode provider : providers) {
            result.add(provider.asText());
        }
        return result;
    }

    private JsonNode readTreeSafely(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            log.warn("Unparsable mfa settings JSON value");
            return objectMapper.createObjectNode();
        }
    }

    private ArrayNode ensureProvidersArray(ObjectNode root) {
        JsonNode existing = root.get("providers");
        if (existing instanceof ArrayNode arrayNode) {
            return arrayNode;
        }
        return root.putArray("providers");
    }

    private boolean containsText(ArrayNode array, String value) {
        for (JsonNode node : array) {
            if (value.equals(node.asText())) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> defaultOrgConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("providers", new ArrayList<String>());
        return config;
    }

    /**
     * Deterministic property_id (PHP uses a UUIDv5 via UuidFactory; this is
     * a UUIDv3 — values differ across implementations but queries always go
     * by the property name, never by property_id, so this is internally
     * consistent. Sharing one MySQL database between JPassbolt and an
     * official PHP instance would yield two rows per property — out of
     * scope).
     */
    private String deterministicUuid(String seed) {
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
    }

    /**
     * Issuer for provisioning URIs: host + path of the configured full base
     * url, colons stripped and trailing slash trimmed
     * (PHP MfaOtpFactory::getIssuer).
     */
    private String deriveIssuer() {
        String fullBaseUrl = settingsProperties.getFullBaseUrl();
        try {
            URI uri = URI.create(fullBaseUrl);
            String issuer = (uri.getHost() != null ? uri.getHost() : fullBaseUrl)
                    + (uri.getPath() != null ? uri.getPath() : "");
            issuer = issuer.replace(":", "");
            while (issuer.endsWith("/")) {
                issuer = issuer.substring(0, issuer.length() - 1);
            }
            return issuer.isEmpty() ? fullBaseUrl : issuer;
        } catch (IllegalArgumentException e) {
            return fullBaseUrl;
        }
    }
}
