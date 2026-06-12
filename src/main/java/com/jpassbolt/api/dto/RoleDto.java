package com.jpassbolt.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * DTOs for the Role read-only endpoint (GET /roles.json).
 * All field names are single lowercase words, so no @JsonProperty is needed.
 * Do NOT add @JsonInclude(NON_NULL): the OpenAPI spec lists "description"
 * as required (the key must be present even when its value is null).
 */
public class RoleDto {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Response {
        private String id;
        private String name;
        private String description;
        private LocalDateTime created;
        private LocalDateTime modified;
    }
}
