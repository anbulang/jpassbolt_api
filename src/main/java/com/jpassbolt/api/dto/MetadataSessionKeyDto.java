package com.jpassbolt.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * DTOs for the Metadata session keys API, matching the OpenAPI schemas
 * metadataSessionKeyIndexAndView (GET/POST /metadata/session-keys.json) and
 * e2eeDataModified (POST /metadata/session-key/{id}.json).
 *
 * Transport only — no business logic. snake_case JSON via @JsonProperty.
 */
public class MetadataSessionKeyDto {

    /**
     * Request body for POST /metadata/session-keys.json — schema
     * {@code requestBodies/add} = e2eeDataOnly: {@code {data}}.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateRequest {
        private String data;
    }

    /**
     * Request body for POST /metadata/session-key/{id}.json — schema
     * {@code requestBodies/update} = e2eeDataModified: {@code {data, modified}}.
     * The {@code modified} timestamp is the client's last-known value, used for
     * optimistic-lock checking (409 when it doesn't match the stored value).
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateRequest {
        private String data;
        private LocalDateTime modified;
    }

    /**
     * Response for GET (array) and POST add (single) — schema
     * metadataSessionKeyIndexAndView:
     * {@code {id, created, modified, data, user_id}} (all required).
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Response {
        private String id;
        private String data;
        private LocalDateTime created;
        private LocalDateTime modified;

        @JsonProperty("user_id")
        private String userId;
    }

    /**
     * Response for POST update — schema e2eeDataModified: {@code {data, modified}}.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ModifiedResponse {
        private String data;
        private LocalDateTime modified;
    }
}
