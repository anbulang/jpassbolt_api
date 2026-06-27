package com.jpassbolt.api.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jpassbolt.api.exception.PassboltApiException;
import com.jpassbolt.api.model.OrganizationSetting;
import com.jpassbolt.api.repository.OrganizationSettingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Organization-wide email notification settings — port of the PHP CE plugin
 * {@code Passbolt\EmailNotificationSettings} (controllers
 * {@code NotificationOrgSettingsGetController} / {@code NotificationOrgSettingsPostController},
 * source {@code DbEmailNotificationSettingsSource}, schema
 * {@code App\Notification\NotificationSettings\CoreNotificationSettingsDefinition::buildSchema}).
 *
 * <p>
 * The settings are NOT entity-backed: they live as a single
 * {@code organization_settings} row keyed by {@code property = "emailNotification"}
 * (PHP {@code DbEmailNotificationSettingsSource::NAMESPACE}) whose {@code value}
 * column holds the JSON-serialized settings map. PHP stores the dotted/nested
 * form there; this Java port stores the flat snake_case form (the keys are
 * exactly the same after PHP's {@code Hash::flatten} '.'→'_' transformation, so
 * the two are isomorphic and there is no nesting to preserve here).
 * </p>
 *
 * <p>
 * Read resolution (PHP {@code EmailNotificationSettings::get}, DB-over-defaults):
 * start from {@link #DEFAULTS}, overlay any stored values, and return a complete
 * flattened {@code Map<String,Object>} of booleans. A missing row or an
 * unreadable JSON value falls back to the pure defaults rather than 500ing
 * (mirrors the keys/types settings services in this codebase).
 * </p>
 *
 * <p>
 * Write (PHP {@code EmailNotificationSettings::save} via
 * {@code DbEmailNotificationSettingsSource::write} →
 * {@code OrganizationSettingsTable::createOrUpdateSetting}): unknown keys are
 * stripped, values coerced to boolean, the payload merged over the current
 * effective settings (PHP {@code array_replace_recursive}), and the single
 * organization_settings row upserted with {@code created_by}/{@code modified_by}
 * stamped to the acting admin. The admin gate itself lives in the controller.
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailNotificationSettingsService {

    /** PHP DbEmailNotificationSettingsSource::NAMESPACE. */
    public static final String ORG_SETTING_PROPERTY = "emailNotification";

    /** Seed for the deterministic property_id (mirrors AccountLocaleService / MetadataKeysSettingsService). */
    private static final String ORG_PROPERTY_ID_SEED = "organization.setting.emailNotification";

    /**
     * The authoritative key set + defaults, mirroring PHP
     * {@code CoreNotificationSettingsDefinition::buildSchema}. 25 boolean keys,
     * already in the flattened snake_case form the API emits. Iteration order is
     * the PHP {@code addField} order (LinkedHashMap) for a stable response shape.
     */
    public static final Map<String, Boolean> DEFAULTS = buildDefaults();

    private final OrganizationSettingRepository organizationSettingRepository;
    private final ObjectMapper objectMapper;

    private static Map<String, Boolean> buildDefaults() {
        Map<String, Boolean> d = new LinkedHashMap<>();
        d.put("purify_subject", false);
        // show controls
        d.put("show_comment", false);
        d.put("show_description", false);
        d.put("show_secret", false);
        d.put("show_uri", false);
        d.put("show_username", false);
        // admin/user controls
        d.put("send_admin_user_setup_completed", true);
        d.put("send_admin_user_recover_abort", true);
        d.put("send_admin_user_recover_complete", true);
        d.put("send_admin_user_disable_user", true);
        d.put("send_admin_user_disable_admin", true);
        // comment controls
        d.put("send_comment_add", true);
        // group controls
        d.put("send_group_delete", true);
        d.put("send_group_user_add", true);
        d.put("send_group_user_delete", true);
        d.put("send_group_user_update", true);
        d.put("send_group_manager_update", true);
        d.put("send_group_manager_requestAddUser", true);
        // password controls
        d.put("send_password_create", false);
        d.put("send_password_share", true);
        d.put("send_password_update", true);
        d.put("send_password_delete", true);
        // user controls
        d.put("send_user_create", true);
        d.put("send_user_recover", true);
        d.put("send_user_recoverComplete", true);
        return d;
    }

    /**
     * The effective organization email notification settings: {@link #DEFAULTS}
     * overlaid with whatever is stored in
     * {@code organization_settings(property="emailNotification")} (PHP
     * {@code EmailNotificationSettings::get}, DB-over-defaults). Always returns a
     * complete, flattened {@code Map<String,Object>} of all 25 boolean keys.
     *
     * @return a non-null, complete flattened settings map
     */
    @Transactional(readOnly = true)
    public Map<String, Object> get() {
        Map<String, Object> effective = new LinkedHashMap<>(DEFAULTS);
        organizationSettingRepository.findByProperty(ORG_SETTING_PROPERTY)
                .map(OrganizationSetting::getValue)
                .map(this::deserializeOrEmpty)
                .ifPresent(stored -> stored.forEach((key, value) -> {
                    // overlay only known keys, coercing whatever was stored
                    if (effective.containsKey(key)) {
                        effective.put(key, asBoolean(value, (Boolean) effective.get(key)));
                    }
                }));
        return effective;
    }

    /**
     * Validate (strip unknown keys + coerce to boolean), merge the payload over
     * the current effective settings, upsert the single organization_settings
     * row, and return the new effective flattened map (PHP post controller:
     * {@code array_replace_recursive(existing, payload)} →
     * {@code EmailNotificationSettings::save} → re-read).
     *
     * @param payload     the posted flat toggle map (unknown keys ignored)
     * @param adminUserId the acting (admin) user id, stamped on the row
     * @return the new effective flattened settings map
     */
    @Transactional
    public Map<String, Object> save(Map<String, ?> payload, String adminUserId) {
        // Start from the current effective settings (defaults overlaid with DB).
        Map<String, Object> merged = get();

        // Merge the payload over it: keep only known keys, coerce to boolean
        // (PHP normalizeBoolean + form whitelist + array_replace_recursive).
        if (payload != null) {
            payload.forEach((key, value) -> {
                if (merged.containsKey(key)) {
                    merged.put(key, asBoolean(value, (Boolean) merged.get(key)));
                }
            });
        }

        upsert(serialize(merged), adminUserId);

        // Re-read for parity with the PHP controller (which queries the DB again).
        return get();
    }

    // ---------------------------------------------------------------------
    // internals
    // ---------------------------------------------------------------------

    /**
     * Deserialize the stored JSON to a flat map, returning an empty map on any
     * read failure so {@link #get()} falls back to pure defaults rather than
     * 500ing on a corrupt row (parity with MetadataKeysSettingsService).
     */
    private Map<String, Object> deserializeOrEmpty(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> raw = objectMapper.readValue(json, new TypeReference<>() {
            });
            return raw != null ? raw : Map.of();
        } catch (Exception e) {
            log.warn("Unreadable emailNotification organization setting, returning defaults: {}",
                    e.getMessage());
            return Map.of();
        }
    }

    /**
     * Coerce a stored/posted value to a boolean (PHP
     * {@code QueryStringComponent::normalizeBoolean}): real booleans pass
     * through; "true"/"1"/"on"/"yes" and 1 are true; everything else falls back
     * (for unknown shapes) or is false (for recognizable falsey strings).
     */
    private static Boolean asBoolean(Object value, Boolean fallback) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.intValue() != 0;
        }
        if (value instanceof String s) {
            String v = s.trim().toLowerCase();
            if (v.equals("true") || v.equals("1") || v.equals("on") || v.equals("yes")) {
                return true;
            }
            if (v.equals("false") || v.equals("0") || v.equals("off") || v.equals("no") || v.isEmpty()) {
                return false;
            }
        }
        return fallback;
    }

    /** Serialize the flattened settings map to the JSON stored in the value column. */
    private String serialize(Map<String, Object> settings) {
        try {
            return objectMapper.writeValueAsString(settings);
        } catch (Exception e) {
            throw new PassboltApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "The Email Notification Settings configs are invalid", e);
        }
    }

    /**
     * Upsert the {@code emailNotification} organization_settings row (PHP
     * {@code createOrUpdateSetting}): patch value + modified_by on an existing
     * row, otherwise insert with created_by = modified_by = actor.
     */
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

    /** Deterministic property_id from a seed (mirrors AccountLocaleService). */
    private static String deterministicUuid(String seed) {
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
