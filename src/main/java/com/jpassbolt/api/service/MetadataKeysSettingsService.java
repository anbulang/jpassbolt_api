package com.jpassbolt.api.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jpassbolt.api.dto.MetadataSettingsDto;
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
 * v5 Metadata <b>keys</b> settings service — read/write of the
 * {@code allow_usage_of_personal_keys} / {@code zero_knowledge_key_share}
 * organization toggles.
 *
 * <p>
 * Port of the PHP {@code MetadataKeysSettingsGetService} /
 * {@code MetadataKeysSettingsSetService} / {@code MetadataKeysSettingsForm}.
 * The settings are NOT entity-backed: they live as a single
 * {@code organization_settings} row keyed by {@code property = "metadataKeys"}
 * whose {@code value} column holds the JSON-serialized two-field body. When no
 * row exists, {@link #getKeysSettings()} returns the sensible defaults
 * ({@code personal = true}, {@code zeroKnowledge = false}) — matching PHP
 * {@code getDefaultSettingsArray()}.
 * </p>
 *
 * <p>
 * Zero-knowledge note: this service never touches OpenPGP material. The
 * optional {@code metadata_private_keys} array that the client may post when
 * disabling zero-knowledge mode carries armored ciphertext that is persisted by
 * the keys domain ({@code MetadataKeyService}) — this settings service only
 * validates/persists the two boolean flags. The admin gate itself is enforced
 * in the controller via {@code userService.isAdmin(getCurrentUserId())}; the
 * write methods here additionally take the acting user id to stamp
 * {@code created_by}/{@code modified_by}.
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetadataKeysSettingsService {

    /** PHP MetadataKeysSettingsGetService::ORG_SETTING_PROPERTY. */
    public static final String ORG_SETTING_PROPERTY = "metadataKeys";

    private final OrganizationSettingRepository organizationSettingRepository;
    private final ObjectMapper objectMapper;

    /**
     * Read the metadata keys settings, falling back to defaults when the
     * organization_settings row is absent or its stored JSON is unreadable
     * (PHP behaviour: any deserialization error logs and returns defaults).
     *
     * @return the keys settings DTO (never null)
     */
    @Transactional(readOnly = true)
    public MetadataSettingsDto.KeysSettings getKeysSettings() {
        return organizationSettingRepository.findByProperty(ORG_SETTING_PROPERTY)
                .map(OrganizationSetting::getValue)
                .map(this::deserializeOrDefault)
                .orElseGet(MetadataKeysSettingsService::defaultSettings);
    }

    /**
     * Validate and persist (upsert) the metadata keys settings.
     *
     * <p>
     * Both booleans are required and must be present (PHP
     * {@code MetadataKeysSettingsForm::validationDefault} — requirePresence +
     * boolean). The acting user id stamps {@code created_by}/{@code modified_by}
     * on the organization_settings row, mirroring PHP
     * {@code createOrUpdateSetting}.
     * </p>
     *
     * @param request the posted settings body
     * @param userId  the acting (admin) user id
     * @return the persisted settings DTO
     * @throws PassboltApiException 400 when a required boolean is missing
     */
    @Transactional
    public MetadataSettingsDto.KeysSettings setKeysSettings(
            MetadataSettingsDto.KeysSettingsUpdate request, String userId) {
        if (request == null) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST, "Could not validate the settings.");
        }
        if (request.getAllowUsageOfPersonalKeys() == null) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "The setting allow_usage_of_personal_keys is required.");
        }
        if (request.getZeroKnowledgeKeyShare() == null) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "The setting zero_knowledge_key_share is required.");
        }

        MetadataSettingsDto.KeysSettings settings = MetadataSettingsDto.KeysSettings.builder()
                .allowUsageOfPersonalKeys(request.getAllowUsageOfPersonalKeys())
                .zeroKnowledgeKeyShare(request.getZeroKnowledgeKeyShare())
                .build();

        upsert(serialize(settings), userId);
        return settings;
    }

    // ---------------------------------------------------------------------
    // internals
    // ---------------------------------------------------------------------

    /** PHP getDefaultSettingsArray(): personal keys allowed, not zero-knowledge. */
    private static MetadataSettingsDto.KeysSettings defaultSettings() {
        return MetadataSettingsDto.KeysSettings.builder()
                .allowUsageOfPersonalKeys(true)
                .zeroKnowledgeKeyShare(false)
                .build();
    }

    /**
     * Deserialize the stored JSON, coercing a missing/unreadable value or any
     * null flag back to the defaults (PHP catches the exception and returns
     * defaults rather than 500ing on a corrupt row).
     */
    private MetadataSettingsDto.KeysSettings deserializeOrDefault(String json) {
        try {
            Map<String, Object> raw = objectMapper.readValue(json, new TypeReference<>() {
            });
            MetadataSettingsDto.KeysSettings defaults = defaultSettings();
            return MetadataSettingsDto.KeysSettings.builder()
                    .allowUsageOfPersonalKeys(asBoolean(raw.get("allow_usage_of_personal_keys"),
                            defaults.getAllowUsageOfPersonalKeys()))
                    .zeroKnowledgeKeyShare(asBoolean(raw.get("zero_knowledge_key_share"),
                            defaults.getZeroKnowledgeKeyShare()))
                    .build();
        } catch (Exception e) {
            log.warn("Unreadable metadataKeys organization setting, returning defaults: {}", e.getMessage());
            return defaultSettings();
        }
    }

    private static Boolean asBoolean(Object value, Boolean fallback) {
        return value instanceof Boolean b ? b : fallback;
    }

    /** Serialize the two-field body to the JSON stored in the value column. */
    private String serialize(MetadataSettingsDto.KeysSettings settings) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("allow_usage_of_personal_keys", settings.getAllowUsageOfPersonalKeys());
        body.put("zero_knowledge_key_share", settings.getZeroKnowledgeKeyShare());
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new PassboltApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Could not serialize the settings.", e);
        }
    }

    /**
     * Upsert the {@code metadataKeys} organization_settings row (PHP
     * {@code createOrUpdateSetting}): patch the value + modified_by on an
     * existing row, otherwise insert with created_by = modified_by = actor.
     */
    private void upsert(String value, String userId) {
        OrganizationSetting setting = organizationSettingRepository.findByProperty(ORG_SETTING_PROPERTY)
                .orElseGet(OrganizationSetting::new);
        if (setting.getId() == null) {
            setting.setProperty(ORG_SETTING_PROPERTY);
            setting.setPropertyId(propertyId(ORG_SETTING_PROPERTY));
            setting.setCreatedBy(userId);
        }
        setting.setValue(value);
        setting.setModifiedBy(userId);
        organizationSettingRepository.save(setting);
    }

    /**
     * Stable {@code property_id} derived from the property name, matching the
     * project's name-based UUID convention (see {@code util/ApiResponse} and
     * {@code MfaService}). PHP derives it from a namespaced UuidFactory hash;
     * the read path keys off {@code property}, so this only needs to be stable
     * and unique per property.
     */
    private static String propertyId(String property) {
        return UUID.nameUUIDFromBytes(property.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
