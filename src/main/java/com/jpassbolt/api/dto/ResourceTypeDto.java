package com.jpassbolt.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * DTOs for resource type endpoints.
 *
 * <p>All JSON field names are single words, so no {@code @JsonProperty}
 * annotations are needed. No {@code @JsonInclude(NON_NULL)}: description,
 * definition and deleted are required keys in the OpenAPI contract and must
 * be serialized even when null.</p>
 */
public class ResourceTypeDto {

    /**
     * Response shape for GET /resource-types.json and
     * GET /resource-types/{id}.json.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Response {

        private String id;

        private String slug;

        private String name;

        private String description;

        /**
         * The JSON Schema definition as a JSON object (deserialized from the
         * raw string stored in the database). Declared as Object so Jackson
         * serializes the JsonNode tree directly instead of a quoted string.
         */
        private Object definition;

        /** Soft-delete timestamp; null for active (v4) types. */
        private LocalDateTime deleted;

        private LocalDateTime created;

        private LocalDateTime modified;
    }
}
