package com.jpassbolt.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * DTOs for the v5 Metadata <b>upgrade</b> (v4&#8594;v5) endpoints
 * ({@code GET/POST /metadata/upgrade/{resources,folders,tags}.json}).
 *
 * <p>
 * Transport-only (Iron Law #3): {@code @JsonProperty} snake_case mapping and
 * nothing else. All validation (UUID/required-field checks), the optimistic-lock
 * conflict detection, the admin gate and the {@code allow_v4_v5_upgrade} setting
 * gate live in {@code MetadataUpgradeService} / the controller, never here.
 * </p>
 *
 * <p>
 * The upgrade is purely <em>additive</em>: the request carries the
 * client-encrypted {@code metadata} blob plus the key reference, and the service
 * writes ONLY the three nullable v5 columns
 * ({@code metadata}/{@code metadata_key_id}/{@code metadata_key_type}) — the v4
 * {@code name}/{@code username}/{@code uri}/{@code description} columns are left
 * untouched so v4 contract behaviour is preserved. The {@code metadata} payload
 * is an armored OpenPGP MESSAGE that the zero-knowledge server stores and
 * forwards but never decrypts.
 * </p>
 *
 * <p>
 * The matching response shapes are the v4 "index and view" projections
 * (OpenAPI {@code resourceV4IndexAndView} / {@code folderV4IndexAndView} /
 * {@code tagLegacy}); the GET index and the POST both return the (re-computed)
 * list of still-upgradeable entities, so the response DTOs are produced by the
 * controller rendering layer rather than duplicated here. This file defines only
 * the array request element shared by every upgrade POST.
 * </p>
 */
public class MetadataUpgradeDto {

    private MetadataUpgradeDto() {
        // DTO holder, not instantiable.
    }

    /**
     * One element of the {@code POST /metadata/upgrade/{model}.json} array body
     * (OpenAPI {@code e2eeMetadataBased} + {@code id} + the optimistic-lock pair).
     *
     * <p>
     * Mirrors the PHP {@code MetadataBatchUpgradeForm} schema: {@code id},
     * {@code metadata}, {@code metadata_key_id}, {@code metadata_key_type},
     * {@code modified} and {@code modified_by} are all required by the form; the
     * service enforces that and rejects a stale {@code modified}/{@code modified_by}
     * with a 409. {@code metadata} is the armored blob (store-and-forward only).
     * </p>
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class UpgradeRequest {

        /** Target entity id (resource/folder/tag) — must be an existing v4 entity. */
        private String id;

        /** Client-encrypted OpenPGP metadata blob (never decrypted by the server). */
        private String metadata;

        @JsonProperty("metadata_key_id")
        private String metadataKeyId;

        @JsonProperty("metadata_key_type")
        private String metadataKeyType;

        /**
         * The {@code modified} timestamp the client last saw — used for the
         * optimistic-lock check against the stored row (409 on mismatch).
         */
        private LocalDateTime modified;

        /**
         * The {@code modified_by} the client last saw — second half of the
         * optimistic-lock check (409 on mismatch).
         */
        @JsonProperty("modified_by")
        private String modifiedBy;
    }
}
