package com.jpassbolt.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * DTOs for the v5 Metadata settings API, matching the OpenAPI schemas
 * {@code metadataKeysSettingsIndex} / {@code metadataKeysSettingsUpdate} /
 * {@code metadataTypesSettingsIndexAndView}.
 *
 * <p>
 * These settings are NOT entity-backed: they live as {@code organization_settings}
 * rows keyed by {@code property = "metadataKeys"} / {@code "metadataTypes"} with the
 * JSON-serialized body stored in the {@code value} column (see the PHP
 * {@code MetadataKeysSettingsGetService} / {@code MetadataTypesSettingsGetService}).
 * </p>
 *
 * <p>
 * DTOs are transport-only: no defaults, validation, or business logic live here.
 * Sensible defaults on a missing org-setting row, boolean/enum validation, and the
 * admin gate are enforced by the settings services / controller (separate files).
 * </p>
 */
public class MetadataSettingsDto {

    /**
     * Response + base request for {@code /metadata/keys/settings.json}
     * (OpenAPI {@code metadataKeysSettingsIndex}).
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KeysSettings {

        @JsonProperty("allow_usage_of_personal_keys")
        private Boolean allowUsageOfPersonalKeys;

        @JsonProperty("zero_knowledge_key_share")
        private Boolean zeroKnowledgeKeyShare;
    }

    /**
     * POST request body for {@code /metadata/keys/settings.json}
     * (OpenAPI {@code metadataKeysSettingsUpdate}).
     *
     * <p>
     * Extends the index shape with an optional {@code metadata_private_keys}
     * array. When zero-knowledge key share is enabled the client supplies the
     * re-encrypted server metadata private key copies here; the server only
     * stores and forwards these armored OpenPGP MESSAGE blobs and never
     * decrypts them.
     * </p>
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KeysSettingsUpdate {

        @JsonProperty("allow_usage_of_personal_keys")
        private Boolean allowUsageOfPersonalKeys;

        @JsonProperty("zero_knowledge_key_share")
        private Boolean zeroKnowledgeKeyShare;

        // Optional; present only when zero_knowledge_key_share toggles require
        // re-sharing the server metadata private key copies.
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonProperty("metadata_private_keys")
        private List<MetadataPrivateKeyEntry> metadataPrivateKeys;
    }

    /**
     * Element of {@code metadata_private_keys}
     * (OpenAPI {@code e2eeDataUserIdMetadataKeyId}). Armored ciphertext is
     * stored/forwarded as-is; the server never decrypts it.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MetadataPrivateKeyEntry {

        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonProperty("user_id")
        private String userId;

        @JsonProperty("metadata_key_id")
        private String metadataKeyId;

        private String data;
    }

    /**
     * Response + request body for {@code /metadata/types/settings.json}
     * (OpenAPI {@code metadataTypesSettingsIndexAndView}). All 14 fields are
     * required by the spec.
     *
     * <p>
     * The four {@code default_*} fields carry the enum string {@code "v4"} or
     * {@code "v5"}; the remaining ten are booleans. Enum/boolean validation is
     * performed by the service, not here.
     * </p>
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TypesSettings {

        @JsonProperty("default_resource_types")
        private String defaultResourceTypes;

        @JsonProperty("default_folder_type")
        private String defaultFolderType;

        @JsonProperty("default_tag_type")
        private String defaultTagType;

        @JsonProperty("default_comment_type")
        private String defaultCommentType;

        @JsonProperty("allow_creation_of_v5_resources")
        private Boolean allowCreationOfV5Resources;

        @JsonProperty("allow_creation_of_v5_folders")
        private Boolean allowCreationOfV5Folders;

        @JsonProperty("allow_creation_of_v5_tags")
        private Boolean allowCreationOfV5Tags;

        @JsonProperty("allow_creation_of_v5_comments")
        private Boolean allowCreationOfV5Comments;

        @JsonProperty("allow_creation_of_v4_resources")
        private Boolean allowCreationOfV4Resources;

        @JsonProperty("allow_creation_of_v4_folders")
        private Boolean allowCreationOfV4Folders;

        @JsonProperty("allow_creation_of_v4_tags")
        private Boolean allowCreationOfV4Tags;

        @JsonProperty("allow_creation_of_v4_comments")
        private Boolean allowCreationOfV4Comments;

        @JsonProperty("allow_v5_v4_downgrade")
        private Boolean allowV5V4Downgrade;

        @JsonProperty("allow_v4_v5_upgrade")
        private Boolean allowV4V5Upgrade;
    }
}
