package com.jpassbolt.api.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * User key policies settings — port of the PHP CE plugin
 * {@code Passbolt\UserKeyPolicies} ({@code UserKeyPoliciesGetSettingsService}
 * + {@code UserKeyPoliciesSettingsDto}), advertising the preferred OpenPGP
 * key parameters clients should use when generating a user key.
 *
 * <p>
 * Values are configurable through {@code jpassbolt.user-key-policies.*} in
 * application.yml and default to the official constants
 * ({@code UserKeyPoliciesSettingsDto::createFromDefault}): type
 * {@value #KEY_TYPE_CURVE}, size {@code null}, curve
 * {@value #KEY_CURVE_ED25519_LEGACY}. Like the PHP form-validation fallback,
 * an invalid configured combination silently degrades to the defaults —
 * clients must always receive a usable policy. This implementation reads
 * config only, so {@code source} is always {@code "default"} (the PHP
 * file/env source distinction does not apply here).
 * </p>
 */
@Slf4j
@Service
public class UserKeyPoliciesService {

    /** PHP UserKeyPoliciesSettingsDto::KEY_TYPE_RSA. */
    public static final String KEY_TYPE_RSA = "rsa";

    /** PHP UserKeyPoliciesSettingsDto::KEY_TYPE_CURVE (the default type). */
    public static final String KEY_TYPE_CURVE = "curve";

    /** PHP UserKeyPoliciesSettingsDto::KEY_CURVE_ED25519_LEGACY (default). */
    public static final String KEY_CURVE_ED25519_LEGACY = "curve25519_legacy+ed25519_legacy";

    /** PHP UserKeyPoliciesSettingsDto::SOURCE_DEFAULT. */
    public static final String SOURCE_DEFAULT = "default";

    private final String preferredKeyType;
    private final String preferredKeySize;
    private final String preferredKeyCurve;

    /**
     * Raw config values; empty strings mean "not set" and resolve to the
     * official defaults (RSA sizes are validated in {@link #getSettings()}).
     */
    public UserKeyPoliciesService(
            @Value("${jpassbolt.user-key-policies.preferred-key-type:curve}") String preferredKeyType,
            @Value("${jpassbolt.user-key-policies.preferred-key-size:}") String preferredKeySize,
            @Value("${jpassbolt.user-key-policies.preferred-key-curve:curve25519_legacy+ed25519_legacy}") String preferredKeyCurve) {
        this.preferredKeyType = preferredKeyType;
        this.preferredKeySize = preferredKeySize;
        this.preferredKeyCurve = preferredKeyCurve;
    }

    /**
     * The user key policies settings map (snake_case, ready for the response
     * body): preferred_key_type / preferred_key_size / preferred_key_curve /
     * source. An unsupported configured combination falls back to the
     * defaults, mirroring the PHP fallback on form-validation failure.
     */
    public Map<String, Object> getSettings() {
        String keyType = preferredKeyType == null ? "" : preferredKeyType.trim().toLowerCase();
        String keyCurve = normalize(preferredKeyCurve);
        Integer keySize = parseKeySize(preferredKeySize);

        if (KEY_TYPE_RSA.equals(keyType)
                && (keySize != null && (keySize == 3072 || keySize == 4096))
                && keyCurve == null) {
            return build(KEY_TYPE_RSA, keySize, null);
        }
        if (KEY_TYPE_CURVE.equals(keyType)
                && keySize == null
                && KEY_CURVE_ED25519_LEGACY.equals(keyCurve)) {
            return build(KEY_TYPE_CURVE, null, keyCurve);
        }

        if (!keyType.isEmpty() && !KEY_TYPE_CURVE.equals(keyType)) {
            log.warn("Invalid user key policies configuration (type={}, size={}, curve={}), "
                    + "falling back to defaults", preferredKeyType, preferredKeySize, preferredKeyCurve);
        }
        return build(KEY_TYPE_CURVE, null, KEY_CURVE_ED25519_LEGACY);
    }

    private Map<String, Object> build(String keyType, Integer keySize, String keyCurve) {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("preferred_key_type", keyType);
        settings.put("preferred_key_size", keySize);
        settings.put("preferred_key_curve", keyCurve);
        settings.put("source", SOURCE_DEFAULT);
        return settings;
    }

    /** Blank / "null" → null (PHP marshalKeySize treats a 0 conversion as null). */
    private Integer parseKeySize(String raw) {
        String value = normalize(raw);
        if (value == null) {
            return null;
        }
        try {
            int size = Integer.parseInt(value);
            return size == 0 ? null : size;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Blank or the literal "null" mean unset (PHP env-var semantics). */
    private String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty() || "null".equalsIgnoreCase(value)) {
            return null;
        }
        return value;
    }
}
