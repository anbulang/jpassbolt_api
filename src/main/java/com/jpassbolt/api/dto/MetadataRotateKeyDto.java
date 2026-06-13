package com.jpassbolt.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * DTOs for the v5 Metadata <b>rotate-key</b> endpoints
 * ({@code GET/POST /metadata/rotate-key/{resources,folders,tags}.json}).
 *
 * <p>
 * Transport-only (Iron Law #3). Rotation re-encrypts an entity's metadata under
 * a fresh (active) shared metadata key after the previous key has been expired
 * or deleted: the client supplies the re-encrypted {@code metadata} blob and the
 * new {@code metadata_key_id}, and the zero-knowledge server merely stores and
 * forwards them (it never decrypts the blob).
 * </p>
 *
 * <p>
 * The request element has the same shape as the upgrade element (PHP
 * {@code MetadataBatchRotateKeyForm extends MetadataBatchUpgradeForm}), with two
 * differences enforced by {@code MetadataRotateKeyService}: the new
 * {@code metadata_key_type} must be {@code shared_key}, and the new
 * {@code metadata_key_id} must reference a currently <em>active</em> metadata key
 * (a conflict yields the {@code tooManyUpdatedEntities} 409). Here
 * {@code modified}/{@code modified_by} are optional (the rotate flow keys off the
 * expired-key membership rather than a strict optimistic-lock requirement), so
 * they are emitted only when present.
 * </p>
 *
 * <p>
 * Index/POST responses use OpenAPI {@code resourceMetadataRotateKey} (resources,
 * adds {@code resource_type_id}), {@code e2eeMetadataBasedCommon} (folders) and
 * {@code tagV5MetadataRotateKey} (tags) — those projections are assembled by the
 * controller rendering layer, so only the shared request element is declared here.
 * </p>
 */
public class MetadataRotateKeyDto {

    private MetadataRotateKeyDto() {
        // DTO holder, not instantiable.
    }

    /**
     * One element of the {@code POST /metadata/rotate-key/{model}.json} array
     * body (OpenAPI {@code e2eeMetadataBased} + {@code id}, optimistic-lock pair
     * optional).
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class RotateRequest {

        /** Target entity id whose metadata is being re-encrypted under a new key. */
        private String id;

        /** Re-encrypted OpenPGP metadata blob (never decrypted by the server). */
        private String metadata;

        /** The new (active) shared metadata key the blob was re-encrypted to. */
        @JsonProperty("metadata_key_id")
        private String metadataKeyId;

        /** Must be {@code shared_key} for rotation (enforced in the service). */
        @JsonProperty("metadata_key_type")
        private String metadataKeyType;

        /** Optional optimistic-lock timestamp the client last saw. */
        private LocalDateTime modified;

        /** Optional optimistic-lock actor the client last saw. */
        @JsonProperty("modified_by")
        private String modifiedBy;
    }
}
