package com.jpassbolt.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * DTOs for the v5 Metadata Keys API. Transport only — no business logic.
 *
 * <p>
 * Maps the OpenAPI schemas: {@code metadataKeyAdd} (CreateRequest),
 * {@code metadataKeyUpdate} (ExpireRequest), {@code metadataKeysIndexAndView}
 * (Response), {@code metadataPrivateKeysIndexAndView} (PrivateKeyResponse),
 * {@code metadataPrivateKeysShortIndex} (PrivateKeyShortResponse),
 * {@code metadataPrivateKeysAdd} array element (CreatePrivatesRequest), and the
 * private-key update body (UpdatePrivateRequest). The server is zero-knowledge:
 * the {@code data}/{@code armored_key} fields carry armored OpenPGP blobs that
 * are stored and forwarded verbatim, never decrypted.
 * </p>
 */
public class MetadataKeyDto {

    /**
     * POST /metadata/keys.json body — create a metadata key together with its
     * per-user encrypted private-key copies (OpenAPI {@code metadataKeyAdd}).
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateRequest {
        private String fingerprint;

        @JsonProperty("armored_key")
        private String armoredKey;

        @JsonProperty("metadata_private_keys")
        private List<PrivateKeyEntry> metadataPrivateKeys;
    }

    /**
     * Element of {@code metadata_private_keys} in a create request
     * (OpenAPI {@code e2eeDataUserId}): one user's encrypted private-key copy.
     * {@code user_id} is nullable (NULL = server key copy).
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PrivateKeyEntry {
        @JsonProperty("user_id")
        private String userId;

        private String data;
    }

    /**
     * PUT /metadata/keys/{id}.json body — mark a key expired
     * (OpenAPI {@code metadataKeyUpdate}). {@code expired} may be null.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExpireRequest {
        private String fingerprint;

        @JsonProperty("armored_key")
        private String armoredKey;

        private LocalDateTime expired;
    }

    /**
     * Metadata key view (OpenAPI {@code metadataKeysIndexAndView}).
     * {@code metadataPrivateKeys} is only populated when
     * {@code contain[metadata_private_keys]} is requested.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Response {
        private String id;
        private String fingerprint;

        @JsonProperty("armored_key")
        private String armoredKey;

        private LocalDateTime created;
        private LocalDateTime modified;
        private LocalDateTime expired;
        private LocalDateTime deleted;

        @JsonProperty("created_by")
        private String createdBy;

        @JsonProperty("modified_by")
        private String modifiedBy;

        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonProperty("metadata_private_keys")
        private List<PrivateKeyResponse> metadataPrivateKeys;
    }

    /**
     * Full private-key view (OpenAPI {@code metadataPrivateKeysIndexAndView}).
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PrivateKeyResponse {
        private String id;

        @JsonProperty("metadata_key_id")
        private String metadataKeyId;

        @JsonProperty("user_id")
        private String userId;

        private String data;
        private LocalDateTime created;
        private LocalDateTime modified;

        @JsonProperty("created_by")
        private String createdBy;

        @JsonProperty("modified_by")
        private String modifiedBy;
    }

    /**
     * Short private-key view (OpenAPI {@code metadataPrivateKeysShortIndex}) —
     * returned by the private-key update endpoint.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PrivateKeyShortResponse {
        @JsonProperty("user_id")
        private String userId;

        private String data;

        @JsonProperty("created_by")
        private String createdBy;

        @JsonProperty("modified_by")
        private String modifiedBy;
    }

    /**
     * Element of the POST /metadata/keys/privates.json array body — create or
     * share a missing per-user private-key copy
     * (OpenAPI {@code metadataPrivateKeysAdd} item, {@code e2eeDataUserIdMetadataKeyId}).
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreatePrivatesRequest {
        @JsonProperty("metadata_key_id")
        private String metadataKeyId;

        @JsonProperty("user_id")
        private String userId;

        private String data;
    }

    /**
     * PUT /metadata/keys/private/{id}.json body — update the encrypted
     * private-key data blob.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdatePrivateRequest {
        private String data;
    }
}
