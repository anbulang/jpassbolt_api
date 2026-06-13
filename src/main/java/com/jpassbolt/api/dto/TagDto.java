package com.jpassbolt.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * DTOs for the EE Tags API, matching the OpenAPI schemas
 * {@code tagLegacy} / {@code tagV5} / {@code tagIndexAndView} (anyOf union)
 * and the {@code PUT}/{@code POST} {@code /tags/{id}.json} request bodies.
 *
 * <p>
 * Transport only — no business logic. JSON is snake_case via
 * {@link JsonProperty}; optional v5 metadata fields are dropped from output
 * with {@link JsonInclude}({@code NON_NULL}). {@code GET /tags.json} returns a
 * {@code List<Object>} mixing {@link LegacyResponse} and {@link V5Response}
 * shapes (the spec's {@code anyOf} union).
 * </p>
 */
public class TagDto {

    /**
     * v4 tag view ({@code tagLegacy}). {@code user_id} is the owner of a
     * personal tag and is null for shared tags.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class LegacyResponse {
        private String id;
        private String slug;

        @JsonProperty("is_shared")
        private Boolean isShared;

        @JsonProperty("user_id")
        private String userId;
    }

    /**
     * v5.1 tag view ({@code tagV5}) — the label is carried in encrypted,
     * zero-knowledge {@code metadata} instead of a plaintext slug.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class V5Response {
        private String id;
        private String metadata;

        @JsonProperty("metadata_key_id")
        private String metadataKeyId;

        @JsonProperty("metadata_key_type")
        private String metadataKeyType;

        @JsonProperty("is_shared")
        private Boolean isShared;
    }

    /**
     * Body of {@code PUT /tags/{id}.json} — rename / reshare a tag. The v5
     * metadata fields are optional; {@code is_shared} toggles personal⇄shared.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class UpdateRequest {
        /** v4 rename target (mutually exclusive with v5 metadata). */
        private String slug;

        private String metadata;

        @JsonProperty("metadata_key_id")
        private String metadataKeyId;

        @JsonProperty("metadata_key_type")
        private String metadataKeyType;

        @JsonProperty("is_shared")
        private Boolean isShared;
    }

    /**
     * Body of {@code POST /tags/{id}.json} — set the tags on a resource. Each
     * entry is either an existing tag reference ({@code {id}}) or a new tag
     * definition (v4 {@code slug} or v5 {@code metadata} fields). The plugin's
     * "set" semantics replace the resource's personal tag list.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AddRequest {
        @JsonProperty("tags")
        private List<TagEntry> tags;
    }

    /**
     * A single element of {@link AddRequest#tags}: an existing tag ({@code id})
     * or a new tag (v4 {@code slug} / v5 {@code metadata} union shape).
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TagEntry {
        /** Reference to an existing tag; when present the other fields are ignored. */
        private String id;

        /** New v4 tag slug ({@code '#'}-prefixed ⇒ shared). */
        private String slug;

        @JsonProperty("is_shared")
        private Boolean isShared;

        // v5.1 new-tag definition (store-and-forward armored metadata).
        private String metadata;

        @JsonProperty("metadata_key_id")
        private String metadataKeyId;

        @JsonProperty("metadata_key_type")
        private String metadataKeyType;
    }
}
