package com.jpassbolt.api.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jpassbolt.api.dto.MetadataSettingsDto;
import com.jpassbolt.api.exception.PassboltApiException;
import com.jpassbolt.api.model.OrganizationSetting;
import com.jpassbolt.api.repository.MetadataKeyRepository;
import com.jpassbolt.api.repository.OrganizationSettingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * v5 Metadata <b>types</b> settings service — read/write of the 14 organization
 * toggles that govern which content-type version (v4 / v5) may be created,
 * upgraded, or downgraded for resources / folders / tags / comments.
 *
 * <p>
 * Port of the PHP {@code MetadataTypesSettingsGetService} /
 * {@code MetadataTypesSettingsSetService} /
 * {@code MetadataTypesSettingsAssertService} / {@code MetadataTypesSettingsForm}.
 * Stored as a single {@code organization_settings} row keyed by
 * {@code property = "metadataTypes"} with the JSON body in the {@code value}
 * column. When absent, {@link #getTypesSettings()} returns the v4 defaults
 * ({@code default_* = "v4"}, {@code allow_creation_of_v5_* = false},
 * {@code allow_creation_of_v4_* = true}, downgrade/upgrade {@code = false}).
 * </p>
 *
 * <p>
 * Cross-domain reads: the upgrade-rotate and tags domains inject this bean
 * read-only to consult {@link #isV4V5UpgradeAllowed()},
 * {@link #isV5ResourceCreationAllowed()}, {@link #isV5FolderCreationAllowed()},
 * {@link #isV5TagCreationAllowed()}, {@link #isV4TagCreationAllowed()}. Those
 * helpers read the persisted settings (or defaults) and apply no business
 * mutation. The admin gate for writes is enforced in the controller.
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetadataTypesSettingsService {

    /** PHP MetadataTypesSettingsGetService::ORG_SETTING_PROPERTY. */
    public static final String ORG_SETTING_PROPERTY = "metadataTypes";

    /** PHP MetadataTypesSettingsDto::V4 / ::V5 (the only allowed default_* values). */
    public static final String V4 = "v4";
    public static final String V5 = "v5";
    private static final Set<String> ALLOWED_VERSIONS = Set.of(V4, V5);

    private final OrganizationSettingRepository organizationSettingRepository;
    private final MetadataKeyRepository metadataKeyRepository;
    private final ObjectMapper objectMapper;

    /**
     * Read the metadata types settings, falling back to the v4 defaults when
     * the organization_settings row is absent or unreadable (PHP behaviour).
     *
     * @return the types settings DTO (never null)
     */
    @Transactional(readOnly = true)
    public MetadataSettingsDto.TypesSettings getTypesSettings() {
        return organizationSettingRepository.findByProperty(ORG_SETTING_PROPERTY)
                .map(OrganizationSetting::getValue)
                .map(this::deserializeOrDefault)
                .orElseGet(MetadataTypesSettingsService::defaultV4Settings);
    }

    /**
     * Validate and persist (upsert) the metadata types settings.
     *
     * <p>
     * Mirrors PHP {@code MetadataTypesSettingsForm::validationDefault} +
     * {@code assertThatAnActiveMetadataKeyExistsWhenV5IsEnabled}:
     * </p>
     * <ul>
     *   <li>all 14 fields required;</li>
     *   <li>the four {@code default_*} values must be {@code "v4"} or
     *       {@code "v5"};</li>
     *   <li>a chosen default version must have its matching
     *       {@code allow_creation_of_v{4,5}_*} flag enabled;</li>
     *   <li>v4 and v5 creation cannot both be disabled for the same entity;</li>
     *   <li>when any v5 capability is enabled, an active (not deleted, not
     *       expired) metadata key must already exist.</li>
     * </ul>
     *
     * @param request the posted settings body (all 14 fields)
     * @param userId  the acting (admin) user id
     * @return the persisted settings DTO
     * @throws PassboltApiException 400 on any validation failure
     */
    @Transactional
    public MetadataSettingsDto.TypesSettings setTypesSettings(
            MetadataSettingsDto.TypesSettings request, String userId) {
        validate(request);
        upsert(serialize(request), userId);
        return request;
    }

    // ---------------------------------------------------------------------
    // cross-domain read helpers (upgrade-rotate + tags inject this bean)
    // ---------------------------------------------------------------------

    /** PHP isV5UpgradeAllowed() — honored by the upgrade domain. */
    @Transactional(readOnly = true)
    public boolean isV4V5UpgradeAllowed() {
        return Boolean.TRUE.equals(getTypesSettings().getAllowV4V5Upgrade());
    }

    /** PHP isV4DowngradeAllowed(). */
    @Transactional(readOnly = true)
    public boolean isV5V4DowngradeAllowed() {
        return Boolean.TRUE.equals(getTypesSettings().getAllowV5V4Downgrade());
    }

    @Transactional(readOnly = true)
    public boolean isV5ResourceCreationAllowed() {
        return Boolean.TRUE.equals(getTypesSettings().getAllowCreationOfV5Resources());
    }

    @Transactional(readOnly = true)
    public boolean isV5FolderCreationAllowed() {
        return Boolean.TRUE.equals(getTypesSettings().getAllowCreationOfV5Folders());
    }

    @Transactional(readOnly = true)
    public boolean isV5TagCreationAllowed() {
        return Boolean.TRUE.equals(getTypesSettings().getAllowCreationOfV5Tags());
    }

    @Transactional(readOnly = true)
    public boolean isV4TagCreationAllowed() {
        return Boolean.TRUE.equals(getTypesSettings().getAllowCreationOfV4Tags());
    }

    // ---------------------------------------------------------------------
    // validation
    // ---------------------------------------------------------------------

    private void validate(MetadataSettingsDto.TypesSettings s) {
        if (s == null) {
            throw badRequest("Could not validate the settings.");
        }

        // 1. all booleans required (PHP requirePresence on every flag).
        requireBoolean(s.getAllowCreationOfV5Resources(), "allow_creation_of_v5_resources");
        requireBoolean(s.getAllowCreationOfV5Folders(), "allow_creation_of_v5_folders");
        requireBoolean(s.getAllowCreationOfV5Tags(), "allow_creation_of_v5_tags");
        requireBoolean(s.getAllowCreationOfV5Comments(), "allow_creation_of_v5_comments");
        requireBoolean(s.getAllowCreationOfV4Resources(), "allow_creation_of_v4_resources");
        requireBoolean(s.getAllowCreationOfV4Folders(), "allow_creation_of_v4_folders");
        requireBoolean(s.getAllowCreationOfV4Tags(), "allow_creation_of_v4_tags");
        requireBoolean(s.getAllowCreationOfV4Comments(), "allow_creation_of_v4_comments");
        requireBoolean(s.getAllowV5V4Downgrade(), "allow_v5_v4_downgrade");
        requireBoolean(s.getAllowV4V5Upgrade(), "allow_v4_v5_upgrade");

        // 2. default_* enum membership (PHP inList + notEmptyString).
        requireVersion(s.getDefaultResourceTypes(), "default_resource_types");
        requireVersion(s.getDefaultFolderType(), "default_folder_type");
        requireVersion(s.getDefaultTagType(), "default_tag_type");
        requireVersion(s.getDefaultCommentType(), "default_comment_type");

        // 3. the chosen default version must be enabled (PHP defaultTypeMustBeEnabled).
        requireDefaultEnabled(s.getDefaultResourceTypes(),
                s.getAllowCreationOfV5Resources(), s.getAllowCreationOfV4Resources(), "default_resource_types");
        requireDefaultEnabled(s.getDefaultFolderType(),
                s.getAllowCreationOfV5Folders(), s.getAllowCreationOfV4Folders(), "default_folder_type");
        requireDefaultEnabled(s.getDefaultTagType(),
                s.getAllowCreationOfV5Tags(), s.getAllowCreationOfV4Tags(), "default_tag_type");
        requireDefaultEnabled(s.getDefaultCommentType(),
                s.getAllowCreationOfV5Comments(), s.getAllowCreationOfV4Comments(), "default_comment_type");

        // 4. v4 and v5 cannot both be disabled for the same entity (PHP atLeastOne).
        requireAtLeastOne(s.getAllowCreationOfV4Resources(), s.getAllowCreationOfV5Resources(),
                "allow_creation_of_v4_resources");
        requireAtLeastOne(s.getAllowCreationOfV4Folders(), s.getAllowCreationOfV5Folders(),
                "allow_creation_of_v4_folders");
        requireAtLeastOne(s.getAllowCreationOfV4Tags(), s.getAllowCreationOfV5Tags(),
                "allow_creation_of_v4_tags");
        requireAtLeastOne(s.getAllowCreationOfV4Comments(), s.getAllowCreationOfV5Comments(),
                "allow_creation_of_v4_comments");

        // 5. an active metadata key must exist when any v5 capability is enabled
        //    (PHP assertThatAnActiveMetadataKeyExistsWhenV5IsEnabled).
        if (isV5Enabled(s) && metadataKeyRepository.countByDeletedIsNullAndExpiredIsNull() == 0) {
            throw badRequest("Unable to save the metadata settings. "
                    + "An active metadata key could not be found, create a key first.");
        }
    }

    /** PHP isV5Enabled(): any v5 creation flag or the v4->v5 upgrade flag. */
    private static boolean isV5Enabled(MetadataSettingsDto.TypesSettings s) {
        return Boolean.TRUE.equals(s.getAllowCreationOfV5Resources())
                || Boolean.TRUE.equals(s.getAllowCreationOfV5Folders())
                || Boolean.TRUE.equals(s.getAllowCreationOfV5Tags())
                || Boolean.TRUE.equals(s.getAllowCreationOfV5Comments())
                || Boolean.TRUE.equals(s.getAllowV4V5Upgrade());
    }

    private void requireBoolean(Boolean value, String field) {
        if (value == null) {
            throw badRequest("The setting " + field + " is required.");
        }
    }

    private void requireVersion(String value, String field) {
        if (value == null || value.isBlank()) {
            throw badRequest("The setting " + field + " is required.");
        }
        if (!ALLOWED_VERSIONS.contains(value)) {
            throw badRequest("The setting " + field + " should be one of the following: v4, v5.");
        }
    }

    private void requireDefaultEnabled(String defaultVersion, Boolean v5Allowed, Boolean v4Allowed, String field) {
        boolean enabled = V5.equals(defaultVersion)
                ? Boolean.TRUE.equals(v5Allowed)
                : Boolean.TRUE.equals(v4Allowed);
        if (!enabled) {
            throw badRequest("The content type for " + field
                    + " needs to be enabled in order to be set as default.");
        }
    }

    private void requireAtLeastOne(Boolean v4Allowed, Boolean v5Allowed, String field) {
        if (!Boolean.TRUE.equals(v4Allowed) && !Boolean.TRUE.equals(v5Allowed)) {
            throw badRequest("Both v4 and v5 settings cannot be disabled for " + field + ".");
        }
    }

    private static PassboltApiException badRequest(String message) {
        return new PassboltApiException(HttpStatus.BAD_REQUEST, message);
    }

    // ---------------------------------------------------------------------
    // (de)serialization + persistence
    // ---------------------------------------------------------------------

    /** PHP defaultV4Settings(). */
    private static MetadataSettingsDto.TypesSettings defaultV4Settings() {
        return MetadataSettingsDto.TypesSettings.builder()
                .defaultResourceTypes(V4)
                .defaultFolderType(V4)
                .defaultTagType(V4)
                .defaultCommentType(V4)
                .allowCreationOfV5Resources(false)
                .allowCreationOfV5Folders(false)
                .allowCreationOfV5Tags(false)
                .allowCreationOfV5Comments(false)
                .allowCreationOfV4Resources(true)
                .allowCreationOfV4Folders(true)
                .allowCreationOfV4Tags(true)
                .allowCreationOfV4Comments(true)
                .allowV5V4Downgrade(false)
                .allowV4V5Upgrade(false)
                .build();
    }

    /**
     * Deserialize the stored JSON; on absence/corruption of individual fields
     * fall back to the v4 defaults for that field (PHP returns defaults wholesale
     * on a decode exception — we are tolerant per-field for robustness).
     */
    private MetadataSettingsDto.TypesSettings deserializeOrDefault(String json) {
        try {
            Map<String, Object> raw = objectMapper.readValue(json, new TypeReference<>() {
            });
            MetadataSettingsDto.TypesSettings d = defaultV4Settings();
            return MetadataSettingsDto.TypesSettings.builder()
                    .defaultResourceTypes(asString(raw.get("default_resource_types"), d.getDefaultResourceTypes()))
                    .defaultFolderType(asString(raw.get("default_folder_type"), d.getDefaultFolderType()))
                    .defaultTagType(asString(raw.get("default_tag_type"), d.getDefaultTagType()))
                    .defaultCommentType(asString(raw.get("default_comment_type"), d.getDefaultCommentType()))
                    .allowCreationOfV5Resources(asBoolean(raw.get("allow_creation_of_v5_resources"),
                            d.getAllowCreationOfV5Resources()))
                    .allowCreationOfV5Folders(asBoolean(raw.get("allow_creation_of_v5_folders"),
                            d.getAllowCreationOfV5Folders()))
                    .allowCreationOfV5Tags(asBoolean(raw.get("allow_creation_of_v5_tags"),
                            d.getAllowCreationOfV5Tags()))
                    .allowCreationOfV5Comments(asBoolean(raw.get("allow_creation_of_v5_comments"),
                            d.getAllowCreationOfV5Comments()))
                    .allowCreationOfV4Resources(asBoolean(raw.get("allow_creation_of_v4_resources"),
                            d.getAllowCreationOfV4Resources()))
                    .allowCreationOfV4Folders(asBoolean(raw.get("allow_creation_of_v4_folders"),
                            d.getAllowCreationOfV4Folders()))
                    .allowCreationOfV4Tags(asBoolean(raw.get("allow_creation_of_v4_tags"),
                            d.getAllowCreationOfV4Tags()))
                    .allowCreationOfV4Comments(asBoolean(raw.get("allow_creation_of_v4_comments"),
                            d.getAllowCreationOfV4Comments()))
                    .allowV5V4Downgrade(asBoolean(raw.get("allow_v5_v4_downgrade"), d.getAllowV5V4Downgrade()))
                    .allowV4V5Upgrade(asBoolean(raw.get("allow_v4_v5_upgrade"), d.getAllowV4V5Upgrade()))
                    .build();
        } catch (Exception e) {
            log.warn("Unreadable metadataTypes organization setting, returning v4 defaults: {}", e.getMessage());
            return defaultV4Settings();
        }
    }

    private static String asString(Object value, String fallback) {
        return value instanceof String str && !str.isBlank() ? str : fallback;
    }

    private static Boolean asBoolean(Object value, Boolean fallback) {
        return value instanceof Boolean b ? b : fallback;
    }

    /** Serialize all 14 fields, key order matching the OpenAPI schema. */
    private String serialize(MetadataSettingsDto.TypesSettings s) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("default_resource_types", s.getDefaultResourceTypes());
        body.put("default_folder_type", s.getDefaultFolderType());
        body.put("default_tag_type", s.getDefaultTagType());
        body.put("default_comment_type", s.getDefaultCommentType());
        body.put("allow_creation_of_v5_resources", s.getAllowCreationOfV5Resources());
        body.put("allow_creation_of_v5_folders", s.getAllowCreationOfV5Folders());
        body.put("allow_creation_of_v5_tags", s.getAllowCreationOfV5Tags());
        body.put("allow_creation_of_v5_comments", s.getAllowCreationOfV5Comments());
        body.put("allow_creation_of_v4_resources", s.getAllowCreationOfV4Resources());
        body.put("allow_creation_of_v4_folders", s.getAllowCreationOfV4Folders());
        body.put("allow_creation_of_v4_tags", s.getAllowCreationOfV4Tags());
        body.put("allow_creation_of_v4_comments", s.getAllowCreationOfV4Comments());
        body.put("allow_v5_v4_downgrade", s.getAllowV5V4Downgrade());
        body.put("allow_v4_v5_upgrade", s.getAllowV4V5Upgrade());
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new PassboltApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Could not serialize the settings.", e);
        }
    }

    /** Upsert the {@code metadataTypes} organization_settings row (PHP createOrUpdateSetting). */
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

    /** Stable name-based property_id (see MetadataKeysSettingsService for rationale). */
    private static String propertyId(String property) {
        return UUID.nameUUIDFromBytes(property.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
